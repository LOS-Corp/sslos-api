package com.laundry.auth.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {
    @Test
    void doesNotConvertDownstreamErrorsIntoAuthenticationFailures() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        JwtAuthenticationEntryPoint entryPoint = mock(JwtAuthenticationEntryPoint.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, entryPoint);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        Claims claims = mock(Claims.class);

        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(jwtService.parseToken("valid-token")).thenReturn(claims);
        when(claims.get("roles", List.class)).thenReturn(List.of("CUSTOMER"));
        when(claims.getSubject()).thenReturn(UUID.randomUUID().toString());
        org.mockito.Mockito.doThrow(new ServletException("Downstream failure"))
                .when(chain).doFilter(request, response);

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, chain))
                .isInstanceOf(ServletException.class)
                .hasMessage("Downstream failure");
        verify(entryPoint, never()).commence(request, response, null);
    }
}
