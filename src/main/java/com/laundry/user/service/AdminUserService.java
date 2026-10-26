package com.laundry.user.service;

import com.laundry.auth.repository.RefreshTokenRepository;
import com.laundry.shared.exception.DuplicateResourceException;
import com.laundry.user.dto.AdminUserDtos.AdminUserDto;
import com.laundry.user.dto.AdminUserDtos.CreateUserRequest;
import com.laundry.user.dto.AdminUserDtos.UpdateUserRequest;
import com.laundry.user.entity.Role;
import com.laundry.user.entity.User;
import com.laundry.user.repository.RoleRepository;
import com.laundry.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Admin creates operator accounts and assigns their role. Customers register
 * themselves and are not managed here. Guards keep at least one active admin
 * and stop an admin from demoting or locking their own account.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {
    /** Roles the management web knows; one per account. */
    public static final List<String> OPERATOR_ROLES = List.of("ADMIN", "OWNER", "STAFF");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;

    @Transactional(readOnly = true)
    public List<AdminUserDto> list(String role, String query) {
        List<String> roles = role == null || role.isBlank() ? OPERATOR_ROLES : List.of(checkRole(role));
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByRoleNames(roles).stream()
                .filter(user -> needle.isEmpty()
                        || user.getFullName().toLowerCase(Locale.ROOT).contains(needle)
                        || user.getEmail().contains(needle)
                        || user.getPhone().contains(needle))
                .map(AdminUserService::toDto)
                .toList();
    }

    @Transactional
    public AdminUserDto create(CreateUserRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String phone = request.phone().trim();
        if (userRepository.existsByEmail(email)) throw new DuplicateResourceException("Email đã được sử dụng");
        if (userRepository.existsByPhone(phone)) throw new DuplicateResourceException("Số điện thoại đã được sử dụng");
        User user = new User(request.fullName().trim(), email, phone, passwordEncoder.encode(request.password()));
        user.addRole(role(request.role()));
        try {
            return toDto(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateResourceException("Email hoặc số điện thoại đã được sử dụng");
        }
    }

    @Transactional
    public AdminUserDto update(UUID id, UpdateUserRequest request) {
        User user = operator(id);
        String phone = request.phone().trim();
        if (!phone.equals(user.getPhone()) && userRepository.existsByPhone(phone)) {
            throw new DuplicateResourceException("Số điện thoại đã được sử dụng");
        }
        String newRole = checkRole(request.role());
        if (!newRole.equals(roleOf(user))) {
            if (isSelf(user)) throw new IllegalArgumentException("Không thể tự đổi vai trò của chính mình");
            if ("ADMIN".equals(roleOf(user))) requireAnotherAdmin(user);
            user.replaceRoles(role(newRole));
            // Old tokens carry the old role; force a fresh login.
            refreshTokenRepository.revokeAllByUser(user);
        }
        user.setFullName(request.fullName().trim());
        user.setPhone(phone);
        return toDto(userRepository.save(user));
    }

    @Transactional
    public AdminUserDto setStatus(UUID id, String status) {
        User user = operator(id);
        if ("LOCKED".equals(status)) {
            if (isSelf(user)) throw new IllegalArgumentException("Không thể tự khóa tài khoản của chính mình");
            if ("ADMIN".equals(roleOf(user))) requireAnotherAdmin(user);
            refreshTokenRepository.revokeAllByUser(user);
        }
        user.setStatus(status);
        return toDto(userRepository.save(user));
    }

    @Transactional
    public void resetPassword(UUID id, String password) {
        User user = operator(id);
        user.changePassword(passwordEncoder.encode(password));
        userRepository.save(user);
        refreshTokenRepository.revokeAllByUser(user);
    }

    private User operator(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản"));
        if (!OPERATOR_ROLES.contains(roleOf(user))) {
            throw new IllegalArgumentException("Chỉ quản lý được tài khoản vận hành, không phải khách hàng");
        }
        return user;
    }

    private void requireAnotherAdmin(User user) {
        boolean countsNow = "ACTIVE".equals(user.getStatus());
        if (userRepository.countActiveAdmins() - (countsNow ? 1 : 0) < 1) {
            throw new IllegalArgumentException("Hệ thống phải còn ít nhất một quản trị viên đang hoạt động");
        }
    }

    private boolean isSelf(User user) {
        try {
            return user.getId().equals(userService.getCurrentUserId());
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private Role role(String name) {
        String checked = checkRole(name);
        return roleRepository.findByName(checked)
                .orElseThrow(() -> new IllegalStateException("Vai trò " + checked + " chưa được cấu hình"));
    }

    private static String checkRole(String role) {
        String upper = role.trim().toUpperCase(Locale.ROOT);
        if (!OPERATOR_ROLES.contains(upper)) throw new IllegalArgumentException("Vai trò không hợp lệ");
        return upper;
    }

    /** Highest-privilege operator role; accounts are expected to hold one. */
    static String roleOf(User user) {
        Set<String> names = new java.util.HashSet<>();
        user.getRoles().forEach(r -> names.add(r.getName()));
        return OPERATOR_ROLES.stream().filter(names::contains).findFirst()
                .orElse(names.stream().sorted().findFirst().orElse(null));
    }

    private static AdminUserDto toDto(User user) {
        return new AdminUserDto(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                roleOf(user), user.getStatus(), user.getCreatedAt());
    }
}
