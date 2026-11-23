package com.laundry.security;

import com.laundry.kiosk.KioskAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Hai cách xác thực:
 * <ul>
 *   <li>{@code Authorization: Bearer <JWT>}: người dùng (khách, nhân viên, chủ cửa hàng, quản trị);</li>
 *   <li>{@code X-Kiosk-Key: <khóa>}: kiosk tại cửa hàng (ROLE_KIOSK).</li>
 * </ul>
 * Token sai thì bỏ qua (request thành ẩn danh); quyền do SecurityConfig quyết định.
 */
@Component
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    public static final String KIOSK_HEADER = "X-Kiosk-Key";

    private final JwtService jwtService;
    private final KioskAuthService kioskAuth;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String kioskKey = request.getHeader(KIOSK_HEADER);
        if (header != null && header.startsWith("Bearer ")) {
            jwtService.parse(header.substring(7).trim()).ifPresent(user -> {
                List<SimpleGrantedAuthority> authorities = user.roles().stream()
                        .map(r -> new SimpleGrantedAuthority(r.authority())).toList();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(user, null, authorities));
            });
        } else if (kioskKey != null && !kioskKey.isBlank()) {
            kioskAuth.authenticate(kioskKey.trim()).ifPresent(kiosk -> SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(kiosk, null,
                            List.of(new SimpleGrantedAuthority("ROLE_KIOSK")))));
        }
        chain.doFilter(request, response);
    }
}
