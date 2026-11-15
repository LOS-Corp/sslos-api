package com.laundry.user.service;

import com.laundry.shared.security.Role;
import com.laundry.user.entity.User;
import com.laundry.user.repository.RoleRepository;
import com.laundry.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Creates the first Admin account on startup, so a fresh database can be used without editing SQL.
 * Runs only while there is no active Admin and {@code app.bootstrap.admin.email} is set
 * (the dev profile sets a default; in prod it comes only from BOOTSTRAP_ADMIN_* variables).
 * An existing account is never changed: further Admins and Owners are created from the management web.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.admin.email:}")
    private String email;
    @Value("${app.bootstrap.admin.password:}")
    private String password;
    private static final String DEFAULT_NAME = "Quản trị viên";

    @Value("${app.bootstrap.admin.full-name:}")
    private String fullName;
    @Value("${app.bootstrap.admin.phone:+84900000001}")
    private String phone;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank()) {
            return;
        }
        if (userRepository.countActiveAdmins() > 0) {
            return;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (password == null || password.length() < 8) {
            log.warn("Bootstrap admin skipped: app.bootstrap.admin.password must be at least 8 characters");
            return;
        }
        if (userRepository.existsByEmail(normalized) || userRepository.existsByPhone(phone.trim())) {
            log.warn("Bootstrap admin skipped: email {} or phone {} already belongs to another account",
                    normalized, phone);
            return;
        }
        String name = fullName == null || fullName.isBlank() ? DEFAULT_NAME : fullName.trim();
        User admin = new User(name,normalized, phone.trim(), passwordEncoder.encode(password));
        admin.addRole(roleRepository.findByName(Role.ADMIN.name())
                .orElseThrow(() -> new IllegalStateException("Role ADMIN is not seeded")));
        userRepository.save(admin);
        log.info("Bootstrap admin created: {} (change the password after the first login)", normalized);
    }
}
