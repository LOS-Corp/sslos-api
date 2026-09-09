package com.laundry.auth;

import com.laundry.user.Role;
import com.laundry.user.RoleRepository;
import com.laundry.user.User;
import com.laundry.user.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public CustomerResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        String phone = request.phone().trim();

        if (!request.password().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("Password confirmation does not match");
        }
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("Email is already registered");
        }
        if (userRepository.existsByPhone(phone)) {
            throw new DuplicateResourceException("Phone is already registered");
        }

        Role customerRole = roleRepository.findByName("CUSTOMER")
            .orElseThrow(() -> new IllegalStateException("CUSTOMER role is not configured"));
        User user = new User(request.fullName().trim(), email, phone, passwordEncoder.encode(request.password()));
        user.addRole(customerRole);

        try {
            return CustomerResponse.from(userRepository.save(user));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateResourceException("Email or phone is already registered");
        }
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.email()))
            .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
            .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
            .orElseThrow(InvalidCredentialsException::new);
        String role = user.getRoles().stream()
            .map(Role::getName)
            .sorted()
            .findFirst()
            .orElse(null);
        return new LoginResponse(jwtService.createToken(user), CustomerResponse.from(user), role);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}