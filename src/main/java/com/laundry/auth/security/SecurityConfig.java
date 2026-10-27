package com.laundry.auth.security;

import com.laundry.shared.security.Role;
import com.laundry.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Stateless JWT security. URL rules mirror the permission matrix in
 * {@code task/09_PHAN_QUYEN_4_VAI_TRO.md}; finer rules (ownership, branch scope of Staff,
 * per-command roles) are enforced in the services. Anything not listed is denied (NFR-13).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String CUSTOMER = Role.CUSTOMER.name();
    private static final String STAFF = Role.STAFF.name();
    private static final String OWNER = Role.OWNER.name();
    private static final String ADMIN = Role.ADMIN.name();

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(UserRepository userRepository) {
        return email -> userRepository.findByEmail(email)
                .map(user -> org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                        .password(user.getPasswordHash())
                        .authorities(user.getRoles().stream().map(role -> "ROLE_" + role.getName())
                                .toArray(String[]::new))
                        .disabled(!"ACTIVE".equals(user.getStatus()))
                        .accountLocked("LOCKED".equals(user.getStatus()))
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(email));
    }

    /** The JWT filter runs inside the security chain only, not a second time as a plain servlet filter. */
    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterServletRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
                                            SecurityErrorResponder errorResponder) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorResponder)
                        .accessDeniedHandler(errorResponder))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ── Public ───────────────────────────────────────────────────────────
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // STOMP handshake; frames are authenticated by the channel interceptor.
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        // Signed by payOS (HMAC) / by the device (HMAC challenge-response), not by JWT.
                        .requestMatchers(HttpMethod.POST, "/api/payments/payos/webhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/devices/activate", "/api/devices/activate/challenge").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/branches", "/api/branches/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/services", "/api/services/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/bookings/timeline-availability",
                                "/api/bookings/recommendations", "/api/bookings/machine-search").permitAll()

                        // ── Machines ─────────────────────────────────────────────────────────
                        .requestMatchers(HttpMethod.GET, "/api/machines/*/events", "/api/machines/*/commands")
                                .hasAnyRole(STAFF, OWNER, ADMIN)
                        .requestMatchers("/api/machines/check-in", "/api/machines/check-in/**").hasRole(CUSTOMER)
                        // Who may send which command is decided per command in RemoteCommandAuthorizer.
                        .requestMatchers(HttpMethod.POST, "/api/machines/*/commands").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/machines", "/api/machines/*",
                                "/api/machines/*/status", "/api/machines/*/qr").permitAll()

                        // ── Customer self-service ────────────────────────────────────────────
                        .requestMatchers("/api/bookings/**", "/api/users/me/bookings").hasRole(CUSTOMER)
                        .requestMatchers("/api/payments/**").hasRole(CUSTOMER)
                        // UC-13: the Owner sees and decides withdrawals; customers use their own wallet.
                        .requestMatchers(HttpMethod.GET, "/api/wallet/withdrawals").hasRole(OWNER)
                        .requestMatchers(HttpMethod.POST, "/api/wallet/withdrawals/*/complete",
                                "/api/wallet/withdrawals/*/reject").hasRole(OWNER)
                        .requestMatchers("/api/wallet/**").hasRole(CUSTOMER)

                        // ── Accounts ─────────────────────────────────────────────────────────
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/auth/change-password").authenticated()
                        .requestMatchers("/api/v1/users/me").authenticated()
                        // Admin manages Owners, Owner manages Staff (checked again in AdminUserService).
                        .requestMatchers("/api/admin/users", "/api/admin/users/**").hasAnyRole(OWNER, ADMIN)

                        // ── Branch operations (Owner manages every branch) ──────────────────
                        .requestMatchers(HttpMethod.GET, "/api/admin/branches", "/api/admin/branches/**")
                                .hasAnyRole(OWNER, ADMIN)
                        .requestMatchers("/api/admin/branches", "/api/admin/branches/**").hasRole(OWNER)
                        .requestMatchers("/api/admin/booking-config/**", "/api/admin/bookings/**").hasRole(OWNER)

                        // ── Machines & IoT ──────────────────────────────────────────────────
                        .requestMatchers("/api/admin/iot-devices", "/api/admin/iot-devices/**").hasRole(ADMIN)
                        .requestMatchers("/api/admin/machines", "/api/admin/machines/**").hasAnyRole(OWNER, ADMIN)

                        // ── Branch staff work (Staff limited to assigned branches in the services) ──
                        .requestMatchers(HttpMethod.POST, "/api/staff/lockers/*/compartments").hasRole(OWNER)
                        .requestMatchers("/api/staff/**").hasAnyRole(STAFF, OWNER)
                        .requestMatchers(HttpMethod.POST, "/api/refunds/*/approve", "/api/refunds/*/reject")
                                .hasRole(OWNER)
                        .requestMatchers("/api/refunds", "/api/refunds/**").hasAnyRole(STAFF, OWNER)
                        // Evidence files: upload by Staff/Owner; who may read which file is decided in AttachmentService.
                        .requestMatchers(HttpMethod.POST, "/api/attachments").hasAnyRole(STAFF, OWNER)
                        .requestMatchers(HttpMethod.GET, "/api/attachments/*").authenticated()

                        // Deny by default: a new endpoint must be added to the matrix on purpose.
                        .anyRequest().denyAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = allowedOrigins == null || allowedOrigins.isBlank()
                ? List.of("http://localhost:3000", "http://localhost:5173")
                : Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "Accept",
                "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers", "X-Trace-Id"));
        configuration.setExposedHeaders(List.of("X-Trace-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
