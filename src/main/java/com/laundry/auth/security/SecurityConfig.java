package com.laundry.auth.security;

import com.laundry.user.entity.Role;
import com.laundry.user.repository.UserRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration for the SSLOS application.
 * Configures stateless JWT-based authentication with role-based access control.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

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
                .authorities(user.getRoles().stream().map(Role::getName).toArray(String[]::new))
                .disabled(!"ACTIVE".equals(user.getStatus()))
                .accountLocked("LOCKED".equals(user.getStatus()))
                .build())
            .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException(email));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
                                          JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint)
            throws Exception {
        return http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(jwtAuthenticationEntryPoint))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public authentication endpoints
                .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/forgot-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/reset-password").permitAll()
                
                // Swagger/OpenAPI
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                
                // Actuator endpoints (for health checks)
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                
                // WebSocket / STOMP endpoint (SockJS requires unauthenticated handshake)
                .requestMatchers("/ws", "/ws/**").permitAll()
                
                // Machine status read — public (kiosk displays, lobby screens)
                .requestMatchers(HttpMethod.GET, "/api/machines", "/api/machines/**").permitAll()
                
                // Machine commands — require authentication
                .requestMatchers(HttpMethod.POST, "/api/machines/**").authenticated()

                // Admin machine CRUD + pairing/self-test/commissioning — admin/owner only
                .requestMatchers("/api/admin/machines", "/api/admin/machines/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_OWNER")
                .requestMatchers("/api/admin/iot-devices", "/api/admin/iot-devices/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_OWNER")

                // ESP32 activation — device authenticates with HMAC challenge-response, not JWT
                .requestMatchers(HttpMethod.POST, "/api/devices/activate", "/api/devices/activate/challenge").permitAll()

                // Branch read — public (kiosks, dropdowns, customer apps)
                .requestMatchers(HttpMethod.GET, "/api/branches", "/api/branches/**").permitAll()

                // Admin branch CRUD — admin/owner only
                .requestMatchers("/api/admin/branches", "/api/admin/branches/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_OWNER")
                // Operator accounts and their roles: platform admin only.
                .requestMatchers("/api/admin/users", "/api/admin/users/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/api/admin/booking-config/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_OWNER")

                // Public service discovery; booking actions are customer-authenticated.
                .requestMatchers(HttpMethod.GET, "/api/services", "/api/services/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/bookings/timeline-availability").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/bookings/recommendations").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/bookings/machine-search").permitAll()
                .requestMatchers("/api/admin/bookings/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_OWNER")
                .requestMatchers("/api/bookings/**", "/api/users/me/bookings").authenticated()
                
                // Transaction & Payment endpoints - public for customer flow
                .requestMatchers("/api/mobile/**").permitAll()
                .requestMatchers("/api/kiosk/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/payments/payos/webhook", "/api/payments/callback/**").permitAll()
                .requestMatchers("/api/payments/**").authenticated()
                .requestMatchers("/api/staff/**").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN", "ROLE_OWNER")
                // Refund endpoints
                .requestMatchers("/api/refunds/**").hasAnyAuthority("ROLE_STAFF", "ROLE_MANAGER", "ROLE_OWNER", "ROLE_ADMIN")
                
                // User profile endpoints - require authentication
                .requestMatchers("/api/v1/users/**").authenticated()
                
                // Protected authentication endpoints
                .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/auth/change-password").authenticated()
                
                // All other requests require authentication
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthenticationFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    /**
     * CORS configuration for multi-client support.
     * Allows configured origins for credentials support.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Set allowed origins from environment variable
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
            configuration.setAllowedOrigins(origins);
        } else {
            // Development fallback - restrict in production
            configuration.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:8080"));
        }
        
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "Accept",
            "Origin",
            "Access-Control-Request-Method",
            "Access-Control-Request-Headers"
        ));
        configuration.setExposedHeaders(Arrays.asList(
            "Access-Control-Allow-Origin",
            "Access-Control-Allow-Credentials"
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
