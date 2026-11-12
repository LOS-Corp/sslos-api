package com.laundry.user.service;

import com.laundry.audit.service.AuditAction;
import com.laundry.audit.service.AuditService;
import com.laundry.audit.service.AuditService.AuditEntry;
import com.laundry.auth.repository.RefreshTokenRepository;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.exception.DuplicateResourceException;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
import com.laundry.user.dto.AdminUserDtos.AdminUserDto;
import com.laundry.user.dto.AdminUserDtos.CreateUserRequest;
import com.laundry.user.dto.AdminUserDtos.UpdateUserRequest;
import com.laundry.user.entity.User;
import com.laundry.user.repository.RoleRepository;
import com.laundry.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Operator accounts. Each level manages the level below (task/09 §3):
 * the Admin manages Admin and Owner accounts, the Owner manages Staff accounts.
 * Customers register themselves and are not managed here. Guards keep at least one
 * active Admin and stop anyone from changing the role of, or locking, their own account.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final String ENTITY = "USER";
    private static final Map<Role, List<Role>> MANAGEABLE = Map.of(
            Role.ADMIN, List.of(Role.ADMIN, Role.OWNER),
            Role.OWNER, List.of(Role.STAFF));

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    /** Roles the caller may create, list and edit. */
    public List<Role> manageableRoles(CurrentUser actor) {
        if (actor.has(Role.ADMIN)) {
            return MANAGEABLE.get(Role.ADMIN);
        }
        return actor.has(Role.OWNER) ? MANAGEABLE.get(Role.OWNER) : List.of();
    }

    @Transactional(readOnly = true)
    public List<AdminUserDto> list(String role, String query, CurrentUser actor) {
        List<Role> visible = manageableRoles(actor);
        List<Role> roles = role == null || role.isBlank() ? visible : List.of(requireManageable(role, actor));
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByRoleNames(roles.stream().map(Role::name).toList()).stream()
                .filter(user -> needle.isEmpty()
                        || user.getFullName().toLowerCase(Locale.ROOT).contains(needle)
                        || user.getEmail().contains(needle)
                        || user.getPhone().contains(needle))
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public AdminUserDto create(CreateUserRequest request, CurrentUser actor) {
        Role role = requireManageable(request.role(), actor);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String phone = request.phone().trim();
        if (userRepository.existsByEmail(email)) throw new DuplicateResourceException("Email đã được sử dụng");
        if (userRepository.existsByPhone(phone)) throw new DuplicateResourceException("Số điện thoại đã được sử dụng");

        User user = new User(request.fullName().trim(), email, phone, passwordEncoder.encode(request.password()));
        user.addRole(roleEntity(role));
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateResourceException("Email hoặc số điện thoại đã được sử dụng");
        }
        audit.record(AuditEntry.of(AuditAction.ACCOUNT_CREATED, ENTITY, user.getId(), actor)
                .withChange(null, Map.of("role", role.name())));
        return toDto(user);
    }

    @Transactional
    public AdminUserDto update(UUID id, UpdateUserRequest request, CurrentUser actor) {
        User user = managed(id, actor);
        Role oldRole = roleOf(user);
        Role newRole = requireManageable(request.role(), actor);
        String phone = request.phone().trim();
        if (!phone.equals(user.getPhone()) && userRepository.existsByPhone(phone)) {
            throw new DuplicateResourceException("Số điện thoại đã được sử dụng");
        }
        if (newRole != oldRole) {
            if (user.getId().equals(actor.id())) {
                throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Không thể tự đổi vai trò của chính mình");
            }
            if (oldRole == Role.ADMIN) requireAnotherAdmin(user);
            user.replaceRoles(roleEntity(newRole));
            refreshTokenRepository.revokeAllByUser(user); // old tokens carry the old role
        }
        user.setFullName(request.fullName().trim());
        user.setPhone(phone);
        userRepository.save(user);
        audit.record(AuditEntry.of(AuditAction.ACCOUNT_UPDATED, ENTITY, user.getId(), actor)
                .withChange(Map.of("role", oldRole.name()), Map.of("role", newRole.name())));
        return toDto(user);
    }

    @Transactional
    public AdminUserDto setStatus(UUID id, String status, CurrentUser actor) {
        User user = managed(id, actor);
        String before = user.getStatus();
        if ("LOCKED".equals(status)) {
            if (user.getId().equals(actor.id())) {
                throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Không thể tự khóa tài khoản của chính mình");
            }
            if (roleOf(user) == Role.ADMIN) requireAnotherAdmin(user);
            refreshTokenRepository.revokeAllByUser(user); // FR-AUTH-04: locking revokes every session
        }
        user.setStatus(status);
        userRepository.save(user);
        audit.record(AuditEntry.of(AuditAction.ACCOUNT_STATUS_CHANGED, ENTITY, user.getId(), actor)
                .withChange(Map.of("status", before), Map.of("status", status)));
        return toDto(user);
    }

    @Transactional
    public void resetPassword(UUID id, String password, CurrentUser actor) {
        User user = managed(id, actor);
        user.changePassword(passwordEncoder.encode(password));
        userRepository.save(user);
        refreshTokenRepository.revokeAllByUser(user);
        audit.record(AuditEntry.of(AuditAction.ACCOUNT_PASSWORD_RESET, ENTITY, user.getId(), actor));
    }

    /** The account must exist and hold a role the caller manages. */
    private User managed(UUID id, CurrentUser actor) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy tài khoản"));
        if (!manageableRoles(actor).contains(roleOf(user))) {
            throw BusinessException.forbidden("Bạn không quản lý tài khoản này");
        }
        return user;
    }

    private Role requireManageable(String value, CurrentUser actor) {
        Role role = Role.parse(value)
                .orElseThrow(() -> BusinessException.of(ErrorCode.VALIDATION_FAILED, "Vai trò không hợp lệ"));
        if (!manageableRoles(actor).contains(role)) {
            throw BusinessException.forbidden("Bạn không được quản lý vai trò " + role.name());
        }
        return role;
    }

    private void requireAnotherAdmin(User user) {
        boolean countsNow = "ACTIVE".equals(user.getStatus());
        if (userRepository.countActiveAdmins() - (countsNow ? 1 : 0) < 1) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Hệ thống phải còn ít nhất một quản trị viên đang hoạt động");
        }
    }

    private com.laundry.user.entity.Role roleEntity(Role role) {
        return roleRepository.findByName(role.name())
                .orElseThrow(() -> new IllegalStateException("Role " + role + " is not seeded"));
    }

    /** Operator accounts hold one role; the highest one wins if data says otherwise. */
    static Role roleOf(User user) {
        return user.getRoles().stream()
                .map(r -> Role.parse(r.getName()))
                .flatMap(java.util.Optional::stream)
                .min(Comparator.naturalOrder())
                .orElse(Role.CUSTOMER);
    }

    private AdminUserDto toDto(User user) {
        return new AdminUserDto(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                roleOf(user).name(), user.getStatus(), user.getCreatedAt());
    }
}
