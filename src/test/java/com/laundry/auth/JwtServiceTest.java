package com.laundry.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.laundry.auth.security.JwtService;
import com.laundry.user.entity.Role;
import com.laundry.user.entity.User;

class JwtServiceTest {

    private JwtService jwtService;
    private User testUser;

    @BeforeEach
    void setUp() {
        // Use a secret that's at least 32 bytes
        jwtService = new JwtService(
            "test-secret-that-is-at-least-32-bytes-long-for-testing",
            "sslos-test",
            900L
        );
        
        testUser = new User("Test User", "test@example.com", "+1234567890", "hash");
        testUser.addRole(new Role("CUSTOMER"));
        
        // Set ID via reflection
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(testUser, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Should create valid JWT token")
    void createToken_ReturnsValidToken() {
        String token = jwtService.createToken(testUser);

        assertThat(token).isNotNull();
        assertThat(token).isNotEmpty();
        assertThat(token.split("\\.")).hasSize(3); // JWT has 3 parts
    }

    @Test
    @DisplayName("Should parse token and extract claims")
    void parseToken_ExtractsClaims() {
        String token = jwtService.createToken(testUser);

        var claims = jwtService.parseToken(token);

        assertThat(claims.getSubject()).isEqualTo(testUser.getId().toString());
        assertThat(claims.get("email")).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("Should reject token with invalid signature")
    void parseToken_ThrowsException_ForInvalidSignature() {
        JwtService differentService = new JwtService(
            "different-secret-that-is-at-least-32-bytes-long",
            "sslos-test",
            900L
        );
        String token = differentService.createToken(testUser);

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> 
            jwtService.parseToken(token)
        );
    }

    @Test
    @DisplayName("Should reject expired token")
    void parseToken_ThrowsException_ForExpiredToken() {
        // Create service with 0 second expiration
        JwtService shortLivedService = new JwtService(
            "test-secret-that-is-at-least-32-bytes-long-for-testing",
            "sslos-test",
            0L
        );
        String token = shortLivedService.createToken(testUser);

        // Small delay to ensure expiration
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> 
            jwtService.parseToken(token)
        );
    }

    @Test
    @DisplayName("Should reject token with wrong issuer")
    void parseToken_ThrowsException_ForWrongIssuer() {
        JwtService differentIssuerService = new JwtService(
            "test-secret-that-is-at-least-32-bytes-long-for-testing",
            "different-issuer",
            900L
        );
        String token = differentIssuerService.createToken(testUser);

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> 
            jwtService.parseToken(token)
        );
    }

    @Test
    @DisplayName("Should throw exception for invalid token format")
    void parseToken_ThrowsException_ForInvalidFormat() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> 
            jwtService.parseToken("invalid.token.format")
        );
    }

    @Test
    @DisplayName("Should throw exception when secret is too short")
    void constructor_ThrowsException_WhenSecretTooShort() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> 
            new JwtService("short", "issuer", 900L)
        );
    }
}
