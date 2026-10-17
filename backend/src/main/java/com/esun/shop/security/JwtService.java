package com.esun.shop.security;

import com.esun.shop.model.Member;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Issues and validates the JWTs used by {@code JwtAuthFilter}. Kept deliberately small -
 * one secret, one claim (subject = member email), one expiry - rather than pulling in
 * Spring Security's full token machinery, consistent with the rest of this codebase's
 * plain-JdbcTemplate, no-framework-magic style.
 */
@Component
public class JwtService {
    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:3600000}") long expirationMs) {
        // HS256 requires a key of at least 256 bits (32 bytes); a short/misconfigured
        // secret should fail fast at startup rather than produce forgeable tokens later.
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(String email) {
        return generateToken(email, Member.Role.BUYER);
    }

    public String generateToken(String email, Member.Role role) {
        return generateToken(email, role, 0);
    }

    /** @param tokenVersion the member's {@code token_version} at issue time; a later bump revokes this token. */
    public String generateToken(String email, Member.Role role, int tokenVersion) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("JWT subject must not be blank");
        }
        if (role == null) {
            throw new IllegalArgumentException("JWT role must not be null");
        }
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(email)
                .claim("role", role.name())
                .claim("ver", tokenVersion)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * @return the member email stored as the token subject.
     * @throws JwtException if the token is missing, malformed, expired, or has an invalid signature.
     */
    public String extractEmail(String token) {
        return extractIdentity(token).email();
    }

    public JwtIdentity extractIdentity(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String email = claims.getSubject();
        if (email == null || email.isBlank()) {
            throw new JwtException("JWT subject is missing") { };
        }
        String roleClaim = claims.get("role", String.class);
        try {
            Integer ver = claims.get("ver", Integer.class);
            return new JwtIdentity(email, Member.Role.valueOf(roleClaim), ver);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new JwtException("JWT role is missing or invalid", ex) { };
        }
    }

    /** @param tokenVersion null for tokens issued before revocation existed; the filter rejects those. */
    public record JwtIdentity(String email, Member.Role role, Integer tokenVersion) { }
}
