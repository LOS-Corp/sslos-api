package com.laundry.user;

import com.laundry.mqtt.service.MqttPublisher;
import com.laundry.shared.exception.DuplicateResourceException;
import com.laundry.user.dto.AdminUserDtos.AdminUserDto;
import com.laundry.user.dto.AdminUserDtos.CreateUserRequest;
import com.laundry.user.dto.AdminUserDtos.UpdateUserRequest;
import com.laundry.user.entity.Role;
import com.laundry.user.entity.User;
import com.laundry.user.repository.RoleRepository;
import com.laundry.user.repository.UserRepository;
import com.laundry.user.service.AdminUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminUserServiceTest {
    private static final String PASSWORD = "Secret#123";

    @Autowired private AdminUserService service;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @MockitoBean private MqttPublisher mqttPublisher;

    private User admin;

    @BeforeEach
    void setUp() {
        for (String name : List.of("ADMIN", "OWNER", "STAFF", "CUSTOMER")) {
            if (roleRepository.findByName(name).isEmpty()) roleRepository.save(new Role(name));
        }
        admin = new User("Admin", "admin-" + UUID.randomUUID() + "@test.vn", phone(), "x");
        admin.addRole(roleRepository.findByName("ADMIN").orElseThrow());
        admin = userRepository.saveAndFlush(admin);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                admin.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsStaffAccountWithOneRoleAndListsIt() {
        AdminUserDto staff = service.create(request("Nhân viên A", "STAFF"));

        assertEquals("STAFF", staff.role());
        assertEquals("ACTIVE", staff.status());
        assertTrue(service.list("STAFF", "nhân viên").stream().anyMatch(u -> u.id().equals(staff.id())));
    }

    @Test
    void rejectsDuplicateEmailAndCustomerRole() {
        CreateUserRequest first = request("Owner", "OWNER");
        service.create(first);
        assertThrows(DuplicateResourceException.class, () -> service.create(
                new CreateUserRequest("Khác", first.email(), phone(), "STAFF", PASSWORD)));
        assertThrows(IllegalArgumentException.class, () -> service.create(request("Khách", "CUSTOMER")));
    }

    @Test
    void changesRoleAndLocksOtherAccounts() {
        AdminUserDto staff = service.create(request("Nhân viên B", "STAFF"));

        AdminUserDto owner = service.update(staff.id(), new UpdateUserRequest("Chủ B", staff.phone(), "OWNER"));
        assertEquals("OWNER", owner.role());
        assertEquals("LOCKED", service.setStatus(staff.id(), "LOCKED").status());
    }

    @Test
    void protectsOwnAccountAndLastAdmin() {
        assertThrows(IllegalArgumentException.class, () -> service.setStatus(admin.getId(), "LOCKED"));
        assertThrows(IllegalArgumentException.class, () -> service.update(admin.getId(),
                new UpdateUserRequest("Admin", admin.getPhone(), "STAFF")));

        // Another admin can be demoted while this one stays active.
        AdminUserDto second = service.create(request("Admin 2", "ADMIN"));
        assertEquals("STAFF", service.update(second.id(),
                new UpdateUserRequest("Admin 2", second.phone(), "STAFF")).role());
    }

    private static CreateUserRequest request(String name, String role) {
        return new CreateUserRequest(name, UUID.randomUUID() + "@test.vn", phone(), role, PASSWORD);
    }

    private static String phone() {
        return "+849" + String.format("%08d", (long) (Math.random() * 100_000_000));
    }
}
