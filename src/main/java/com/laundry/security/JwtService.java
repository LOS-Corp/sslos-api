package com.laundry.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Access token HS256: sub = userId, email, roles. Refresh token là mã ngẫu nhiên lưu DB (AuthService). */
@Service
public class JwtService {

    private final SecretKey key;
    private final String issuer;
    private final long expirationSeconds;
    private final Clock clock;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.issuer}") String issuer,
                      @Value("${app.jwt.expiration-seconds}") long expirationSeconds,
                      Clock clock) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        this.key = Keys.hmacShaKeyFor(bytes);
        this.issuer = issuer;
        this.expirationSeconds = expirationSeconds;
        this.clock = clock;
    }

    public long expirationSeconds() {
        return expirationSeconds;
    }

    public String issue(UUID userId, String email, Collection<Role> roles) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .claim("email", email)
                .claim("roles", roles.stream().map(Role::name).toList())
                .signWith(key)
                .compact();
    }

    public Optional<Actor.User> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).requireIssuer(issuer)
                    .clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            Set<Role> roles = EnumSet.noneOf(Role.class);
            Object raw = claims.get("roles");
            if (raw instanceof List<?> list) {
                for (Object r : list) {
                    try {
                        roles.add(Role.valueOf(String.valueOf(r)));
                    } catch (IllegalArgumentException ignored) {
                        // vai trò không còn tồn tại
                    }
                }
            }
            return Optional.of(new Actor.User(UUID.fromString(claims.getSubject()),
                    claims.get("email", String.class), Set.copyOf(roles)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
