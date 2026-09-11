package com.laundry.auth.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Custom authentication entry point that returns JSON error response
 * when authentication fails (401 Unauthorized).
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                        AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        
        // Return simple JSON error response
        String json = String.format(
            "{\"timestamp\":\"%s\",\"status\":401,\"message\":\"Authentication required\",\"fields\":{}}",
            java.time.OffsetDateTime.now().toString()
        );
        response.getWriter().write(json);
    }
}
