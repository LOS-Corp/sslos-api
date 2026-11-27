package com.laundry.user;

import com.laundry.audit.AuditService;
import com.laundry.auth.RefreshTokenRepository;
import com.laundry.common.Phones;
import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.security.Actor;
import com.laundry.security.Role;
import com.laundry.user.UserDtos.CreateUser;
import com.laundry.user.UserDtos.UserView;
import com.laundry.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final WalletService wallets;
    private final AuditService audit;
    private final Clock clock;

    public User get(UUID id) {
        return users.findById(id).orElseThrow(() -> BusinessException.notFound("tài khoản"));
    }

    /** Tạo tài khoản; khách hàng có ví ngay (§15.2). */
    @Transactional
    public User create(String fullName, String email, String rawPhone, String password, Set<Role> roles) {
        String phone = Phones.normalize(rawPhone);
        String normalizedEmail = email.trim().toLowerCase();
        if (users.emailTaken(normalizedEmail)) throw new BusinessException(ErrorCode.EMAIL_TAKEN);
        if (users.existsByPhone(phone)) throw new BusinessException(ErrorCode.PHONE_TAKEN);
        User u = new User();
        u.setFullName(fullName.trim());
        u.setEmail(normalizedEmail);
        u.setPhone(phone);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setRoles(EnumSet.copyOf(roles));
        users.saveAndFlush(u);
        if (roles.contains(Role.CUSTOMER)) wallets.open(u.getId());
        return u;
    }

    @Transactional
    public UserView updateProfile(UUID id, UserDtos.UpdateProfile body) {
        User u = get(id);
        String phone = Phones.normalize(body.phone());
        if (!phone.equals(u.getPhone()) && users.existsByPhone(phone)) throw new BusinessException(ErrorCode.PHONE_TAKEN);
        u.setFullName(body.fullName().trim());
        u.setPhone(phone);
        return UserView.of(u);
    }

    // ---- Quản trị ----

    @Transactional(readOnly = true)
    public Page<User> search(Role role, String q, int page, int size) {
        // Không truyền null cho :q — Postgres không suy được kiểu tham số trong concat/lower và trả lỗi; "" khớp mọi dòng.
        return users.search(role, q == null ? "" : q.trim(),
                PageRequest.of(page, Math.min(size, 100), Sort.by("createdAt").descending()));
    }

    @Transactional
    public UserView adminCreate(CreateUser body) {
        User u = create(body.fullName(), body.email(), body.phone(), body.password(), body.roles());
        audit.user("USER_CREATED", "user", u.getId().toString(), "Tạo tài khoản " + u.getEmail(), null,
                Map.of("roles", body.roles()));
        return UserView.of(u);
    }

    @Transactional
    public UserView updateRoles(UUID id, Set<Role> roles, String reason) {
        User u = get(id);
        if (u.getId().equals(Actor.userId()) && !roles.contains(Role.ADMIN) && u.getRoles().contains(Role.ADMIN)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Không thể tự bỏ quyền quản trị của chính mình");
        }
        Set<Role> before = u.roleSet();
        u.getRoles().clear();
        u.getRoles().addAll(roles);
        if (roles.contains(Role.CUSTOMER)) wallets.open(u.getId());
        audit.user("USER_ROLES_CHANGED", "user", id.toString(), Reasons.require(reason),
                Map.of("roles", before), Map.of("roles", roles));
        return UserView.of(u);
    }

    /** Quản trị viên sửa họ tên, số điện thoại của một tài khoản (email là định danh đăng nhập, không đổi). */
    @Transactional
    public UserView adminUpdateProfile(UUID id, String fullName, String rawPhone, String reason) {
        User u = get(id);
        String phone = Phones.normalize(rawPhone);
        if (!phone.equals(u.getPhone()) && users.existsByPhone(phone)) throw new BusinessException(ErrorCode.PHONE_TAKEN);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("fullName", u.getFullName());
        before.put("phone", u.getPhone());
        u.setFullName(fullName.trim());
        u.setPhone(phone);
        audit.user("USER_PROFILE_CHANGED", "user", id.toString(), Reasons.require(reason),
                before, Map.of("fullName", u.getFullName(), "phone", phone));
        return UserView.of(u);
    }

    /** Quản trị viên đặt lại mật khẩu: mọi phiên đang mở của người đó bị đăng xuất. Không ghi mật khẩu vào nhật ký. */
    @Transactional
    public void adminResetPassword(UUID id, String password, String reason) {
        User u = get(id);
        u.setPasswordHash(passwordEncoder.encode(password));
        refreshTokens.revokeAll(id, clock.instant());
        audit.user("USER_PASSWORD_RESET", "user", id.toString(), Reasons.require(reason), null, null);
    }

    /**
     * Quản trị viên khóa (until trong tương lai) hoặc gỡ khóa (until null) quyền đặt trước của khách vì vi phạm.
     * Khách vẫn đăng nhập, xem đơn, dùng ví; đơn đã đặt giữ nguyên.
     */
    @Transactional
    public UserView setBookingBan(UUID id, java.time.Instant until, String rawReason) {
        String reason = Reasons.require(rawReason);
        User u = get(id);
        if (!u.getRoles().contains(Role.CUSTOMER)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Chỉ khóa quyền đặt lịch của tài khoản khách hàng");
        }
        if (until != null && !until.isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Thời hạn khóa phải ở tương lai");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("bookingBannedUntil", u.getBookingBannedUntil());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("bookingBannedUntil", until);
        u.setBookingBannedUntil(until);
        u.setBookingBanReason(until == null ? null : reason);
        u.setBookingBannedBy(until == null ? null : Actor.userId());
        audit.user(until == null ? "USER_BOOKING_UNBANNED" : "USER_BOOKING_BANNED", "user", id.toString(), reason,
                before, after);
        return UserView.of(u);
    }

    // ---- Chủ cửa hàng: nhân viên ----

    @Transactional(readOnly = true)
    public java.util.List<UserView> staff() {
        return users.search(Role.STAFF, "", PageRequest.of(0, 200, Sort.by("fullName"))).stream()
                .map(UserView::of).toList();
    }

    @Transactional
    public UserView ownerCreateStaff(UserDtos.CreateStaff body) {
        User u = create(body.fullName(), body.email(), body.phone(), body.password(), EnumSet.of(Role.STAFF));
        audit.user("STAFF_CREATED", "user", u.getId().toString(), "Tạo tài khoản nhân viên " + u.getEmail(), null,
                Map.of("roles", u.roleSet()));
        return UserView.of(u);
    }

    /** Chủ cửa hàng khóa / mở tài khoản nhân viên; tài khoản có quyền Chủ cửa hàng hoặc Quản trị viên thì không. */
    @Transactional
    public UserView ownerUpdateStaffStatus(UUID id, User.Status status, String reason) {
        requireStaffOnly(get(id));
        return updateStatus(id, status, reason);
    }

    @Transactional
    public void ownerResetStaffPassword(UUID id, String password, String reason) {
        requireStaffOnly(get(id));
        adminResetPassword(id, password, reason);
    }

    public User requireStaff(UUID id) {
        User u = get(id);
        if (!u.getRoles().contains(Role.STAFF) && !u.getRoles().contains(Role.OWNER)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Tài khoản này không phải nhân viên");
        }
        return u;
    }

    private static void requireStaffOnly(User u) {
        if (!u.getRoles().contains(Role.STAFF) || u.getRoles().contains(Role.OWNER) || u.getRoles().contains(Role.ADMIN)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Chủ cửa hàng chỉ quản lý tài khoản nhân viên");
        }
    }

    @Transactional
    public UserView updateStatus(UUID id, User.Status status, String reason) {
        User u = get(id);
        if (u.getId().equals(Actor.userId())) throw new BusinessException(ErrorCode.BAD_REQUEST, "Không thể tự khóa chính mình");
        User.Status before = u.getStatus();
        u.setStatus(status);
        if (status != User.Status.ACTIVE) refreshTokens.revokeAll(id, clock.instant());
        audit.user("USER_STATUS_CHANGED", "user", id.toString(), Reasons.require(reason),
                Map.of("status", before), Map.of("status", status));
        return UserView.of(u);
    }
}
