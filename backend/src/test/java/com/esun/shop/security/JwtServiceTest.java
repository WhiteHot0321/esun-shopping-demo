package com.esun.shop.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain unit tests for {@link JwtService} - no Spring context needed since it's a small,
 * self-contained component (one secret, one claim, one expiry).
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-must-be-at-least-32-bytes-long";

    @Test
    void generateToken_thenExtractEmail_roundTrips() {
        JwtService jwtService = new JwtService(SECRET, 3_600_000L);

        String token = jwtService.generateToken("user@example.com");

        assertThat(token).isNotBlank();
        assertThat(jwtService.extractEmail(token)).isEqualTo("user@example.com");
    }

    @Test
    void extractEmail_expiredToken_throws() throws InterruptedException {
        JwtService jwtService = new JwtService(SECRET, 1L); // expires almost immediately

        String token = jwtService.generateToken("user@example.com");
        Thread.sleep(5);

        assertThatThrownBy(() -> jwtService.extractEmail(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void extractEmail_tamperedToken_throws() {
        JwtService jwtService = new JwtService(SECRET, 3_600_000L);
        String token = jwtService.generateToken("user@example.com");
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> jwtService.extractEmail(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void extractEmail_tokenSignedWithDifferentSecret_throws() {
        JwtService issuer = new JwtService(SECRET, 3_600_000L);
        JwtService verifier = new JwtService("a-completely-different-secret-key-of-32-bytes!!", 3_600_000L);

        String token = issuer.generateToken("user@example.com");

        assertThatThrownBy(() -> verifier.extractEmail(token))
                .isInstanceOf(JwtException.class);
    }
}
