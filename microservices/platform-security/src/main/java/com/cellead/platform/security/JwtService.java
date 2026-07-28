package com.cellead.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

public final class JwtService {
    private final SecretKey key;
    private final long expirationSeconds;

    public JwtService(String secret, long expirationSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    public String generate(Long id, String username, String role) {
        return generate(id, username, role, "access", expirationSeconds);
    }

    public String generateRefresh(Long id, String username, String role, long refreshExpirationSeconds) {
        return generate(id, username, role, "refresh", refreshExpirationSeconds);
    }

    private String generate(Long id, String username, String role, String type, long lifetime) {
        Instant now = Instant.now();
        return Jwts.builder().subject(username).claim("uid", id).claim("role", role).claim("token_type", type)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(lifetime)))
                .signWith(key).compact();
    }

    public AuthenticatedUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (!"access".equals(claims.get("token_type", String.class))) {
            throw new IllegalArgumentException("Access token required");
        }
        return user(claims);
    }

    public AuthenticatedUser parseRefresh(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (!"refresh".equals(claims.get("token_type", String.class))) {
            throw new IllegalArgumentException("Refresh token required");
        }
        return user(claims);
    }

    private AuthenticatedUser user(Claims claims) {
        return new AuthenticatedUser(claims.get("uid", Long.class), claims.getSubject(), claims.get("role", String.class));
    }
}
