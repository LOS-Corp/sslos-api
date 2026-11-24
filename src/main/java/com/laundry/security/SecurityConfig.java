package com.laundry.security;

import com.laundry.common.error.ApiError;
import com.laundry.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Quyền theo tiền tố đường dẫn; quyền chi tiết (đơn của ai, hạn mức hoàn tiền...) kiểm trong service.
 * <pre>
 * /api/public/**, /api/auth/(register|login|refresh|forgot-password|reset-password)   ai cũng gọi được
 * /api/devices/register/**   bo mạch tự đăng ký      /api/payments/payos/webhook   payOS
 * /api/kiosk/**   kiosk          /api/staff/**  nhân viên + chủ cửa hàng
 * /api/owner/**   chủ cửa hàng   /api/admin/**  quản trị hệ thống
 * /api/admin/machines/{code}/(provisioning|pairing|self-test|commission|maintenance|unpair)   quản trị + chủ cửa hàng
 * /api/**         người dùng đã đăng nhập (khách hàng: /api/me, /api/orders, /api/wallet...)
 * </pre>
 * Không khớp luật nào thì từ chối.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** AuthFilter chỉ chạy trong chuỗi security, không chạy thêm lần nữa như servlet filter thường. */
    @Bean
    FilterRegistrationBean<AuthFilter> authFilterRegistration(AuthFilter filter) {
        FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthFilter authFilter, ObjectMapper mapper)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> write(mapper, req, res, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, ex) -> write(mapper, req, res, ErrorCode.FORBIDDEN)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/devices/register", "/api/devices/register/challenge")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/payments/payos/webhook").permitAll()
                        .requestMatchers("/api/dev/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/actuator/health").permitAll()
                        .requestMatchers("/api/kiosk/**").hasRole("KIOSK")
                        // Lắp đặt máy (MachineDeploymentController): web quản lý gọi /api/admin cho cả chủ cửa hàng.
                        .requestMatchers("/api/admin/machines/*/provisioning", "/api/admin/machines/*/pairing",
                                "/api/admin/machines/*/self-test", "/api/admin/machines/*/commission",
                                "/api/admin/machines/*/maintenance", "/api/admin/machines/*/unpair")
                        .hasAnyRole(Role.ADMIN.name(), Role.OWNER.name())
                        .requestMatchers("/api/admin/**").hasRole(Role.ADMIN.name())
                        // Máy (giá, gói sấy thêm, lịch bảo trì, profile giá): Quản trị viên làm được như chủ cửa hàng.
                        .requestMatchers("/api/owner/machines/**").hasAnyRole(Role.OWNER.name(), Role.ADMIN.name())
                        .requestMatchers("/api/owner/**").hasRole(Role.OWNER.name())
                        // Gửi lệnh / đổi trạng thái máy: thêm Quản trị viên (trang Chi tiết máy).
                        .requestMatchers("/api/staff/machines/*/commands", "/api/staff/machines/*/status")
                        .hasAnyRole(Role.STAFF.name(), Role.OWNER.name(), Role.ADMIN.name())
                        .requestMatchers("/api/staff/**").hasAnyRole(Role.STAFF.name(), Role.OWNER.name())
                        .requestMatchers("/api/**").hasAnyRole(Role.CUSTOMER.name(), Role.STAFF.name(),
                                Role.OWNER.name(), Role.ADMIN.name())
                        .anyRequest().denyAll())
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static void write(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res, ErrorCode code)
            throws IOException {
        res.setStatus(code.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getOutputStream(), ApiError.of(code, code.message(), req.getRequestURI()));
    }
}
