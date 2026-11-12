package com.laundry.user;

import com.laundry.audit.repository.AuditLogRepository;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.exception.DuplicateResourceException;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
import com.laundry.support.IntegrationTest;
import com.laundry.user.dto.AdminUserDtos.AdminUserDto;
import com.laundry.user.dto.AdminUserDtos.CreateUserRequest;
import com.laundry.user.dto.AdminUserDtos.UpdateUserRequest;
import com.laundry.user.entity.User;
import com.laundry.user.repository.RoleRepository;
import com.laundry.user.repository.UserRepository;
import com.laundry.user.service.AdminUserService;
import com.laundry.user.service.BranchScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each level manages the level below: Admin → Admin/Owner, Owner → Staff (task/09). Single store: no branch assignment. */
@IntegrationTest
@Transactional
class AdminUserServiceTest {
    private static final String PASSWORD = "Secret#123";

    @Autowired private AdminUserService service;
    @Autowired private BranchScopeService branchScope;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private AuditLogRepository auditLogs;

    private CurrentUser admin;
    private CurrentUser owner;

    @BeforeEach
    void setUp() {
        admin = CurrentUser.of(account("Admin", "ADMIN").getId(), Role.ADMIN);
        owner = CurrentUser.of(account("Owner", "OWNER").getId(), Role.OWNER);
    }

    @Test
    @DisplayName("Owner creates Staff, who then work across the whole store")
    void ownerCreatesStaffForTheStore() {
        AdminUserDto staff = service.create(staffRequest("Nhân viên A"), owner);

        assertThat(staff.role()).isEqualTo("STAFF");
        CurrentUser staffUser = CurrentUser.of(staff.id(), Role.STAFF);
        assertThat(branchScope.canAccess(staffUser, UUID.randomUUID())).isTrue();
        assertThat(branchScope.visibleBranches(staffUser)).isEmpty();
        CurrentUser customer = CurrentUser.of(UUID.randomUUID(), Role.CUSTOMER);
        assertThat(branchScope.canAccess(customer, UUID.randomUUID())).isFalse();
        assertThat(service.list("STAFF", "nhân viên", owner)).extracting(AdminUserDto::id).contains(staff.id());
        assertThat(auditLogs.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("USER", staff.id().toString()))
                .extracting(entry -> entry.getAction()).containsExactly("ACCOUNT_CREATED");
    }

    @Test
    @DisplayName("Admin manages Admin/Owner only; Owner manages Staff only; nobody creates CUSTOMER or MANAGER")
    void eachLevelManagesTheLevelBelow() {
        assertThat(service.manageableRoles(admin)).containsExactly(Role.ADMIN, Role.OWNER);
        assertThat(service.manageableRoles(owner)).containsExactly(Role.STAFF);

        assertThat(service.create(request("Owner 2", "OWNER"), admin).role()).isEqualTo("OWNER");
        assertForbidden(() -> service.create(staffRequest("Staff by admin"), admin));
        assertForbidden(() -> service.create(request("Owner by owner", "OWNER"), owner));
        assertThatThrownBy(() -> service.create(request("Khách", "CUSTOMER"), admin))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(request("Quản lý", "MANAGER"), admin))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("Owner edits and locks Staff")
    void ownerEditsAndLocksStaff() {
        AdminUserDto staff = service.create(staffRequest("Nhân viên B"), owner);

        AdminUserDto renamed = service.update(staff.id(),
                new UpdateUserRequest("Nhân viên B2", staff.phone(), "STAFF"), owner);
        assertThat(renamed.fullName()).isEqualTo("Nhân viên B2");
        assertThat(service.setStatus(staff.id(), "LOCKED", owner).status()).isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("Rejects duplicate email; protects own account and the last active Admin")
    void protectsOwnAccountAndLastAdmin() {
        CreateUserRequest first = request("Owner 3", "OWNER");
        service.create(first, admin);
        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("Khác", first.email(), phone(), "OWNER", PASSWORD), admin))
                .isInstanceOf(DuplicateResourceException.class);

        assertThatThrownBy(() -> service.setStatus(admin.id(), "LOCKED", admin)).isInstanceOf(BusinessException.class);
        User self = userRepository.findById(admin.id()).orElseThrow();
        assertThatThrownBy(() -> service.update(admin.id(),
                new UpdateUserRequest("Admin", self.getPhone(), "OWNER"), admin))
                .isInstanceOf(BusinessException.class);

        AdminUserDto second = service.create(request("Admin 2", "ADMIN"), admin);
        assertThat(service.update(second.id(),
                new UpdateUserRequest("Admin 2", second.phone(), "OWNER"), admin).role()).isEqualTo("OWNER");
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private User account(String name, String role) {
        User user = new User(name, UUID.randomUUID() + "@test.vn", phone(), "x");
        user.addRole(roleRepository.findByName(role).orElseThrow());
        return userRepository.saveAndFlush(user);
    }

    private static CreateUserRequest request(String name, String role) {
        return new CreateUserRequest(name, UUID.randomUUID() + "@test.vn", phone(), role, PASSWORD);
    }

    private static CreateUserRequest staffRequest(String name) {
        return new CreateUserRequest(name, UUID.randomUUID() + "@test.vn", phone(), "STAFF", PASSWORD);
    }

    private static String phone() {
        return "+849" + String.format("%08d", (long) (Math.random() * 100_000_000));
    }
}
