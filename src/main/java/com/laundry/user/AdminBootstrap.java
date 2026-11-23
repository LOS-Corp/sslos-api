package com.laundry.user;

import com.laundry.security.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Tạo tài khoản quản trị đầu tiên từ BOOTSTRAP_ADMIN_* nếu email đó chưa có. Không bao giờ ghi đè. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap {

    private final UserService users;
    private final UserRepository repository;

    @Value("${app.bootstrap.admin-email:}")
    private String email;
    @Value("${app.bootstrap.admin-password:}")
    private String password;
    @Value("${app.bootstrap.admin-phone:+84900000000}")
    private String phone;
    @Value("${app.bootstrap.admin-roles:ADMIN,OWNER}")
    private String roles;

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (email == null || email.isBlank() || password == null || password.isBlank()) return;
        if (repository.findByEmail(email.trim()).isPresent()) return;
        Set<Role> set = Arrays.stream(roles.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Role::valueOf).collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
        users.create("Quản trị", email.trim(), phone, password, set);
        log.info("Bootstrap account {} created with roles {}", email, set);
    }
}
