package com.esun.shop.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start the {@code prod} profile with configuration that would be unsafe on the public internet:
 * a forgeable JWT secret, the well-known dev database password, a payment sandbox that lets buyers mark their own
 * orders paid, or CORS that trusts every origin.
 *
 * <p>{@code application-prod.yml} already removes the dev defaults, so a forgotten variable fails on an unresolved
 * placeholder; this check covers values that are set but weak or dev-only. Every violation is reported at once and
 * names only the setting - never its value - because startup failures end up in logs and CI output.
 */
@Component
@Profile("prod")
public class ProductionConfigValidator {

    static final int MIN_JWT_SECRET_BYTES = 32;
    private static final Set<String> WEAK_DB_PASSWORDS = Set.of("123456", "password", "root", "changeme");
    private static final List<String> ECPAY_REQUIRED = List.of(
            "ecpay.merchant-id", "ecpay.hash-key", "ecpay.hash-iv",
            "ecpay.payment-url", "ecpay.callback-url", "ecpay.return-url");

    private final Environment env;

    public ProductionConfigValidator(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void validateOnStartup() {
        List<String> violations = validate(env);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Refusing to start with unsafe production configuration:\n - "
                    + String.join("\n - ", violations));
        }
    }

    static List<String> validate(Environment env) {
        List<String> violations = new ArrayList<>();

        String jwtSecret = env.getProperty("jwt.secret", "");
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            violations.add("jwt.secret (JWT_SECRET) must be at least " + MIN_JWT_SECRET_BYTES + " bytes");
        } else if (jwtSecret.startsWith("dev-only")) {
            violations.add("jwt.secret (JWT_SECRET) is the well-known dev-only value");
        }

        String dbPassword = env.getProperty("spring.datasource.password", "");
        if (dbPassword.isBlank()) {
            violations.add("spring.datasource.password (DB_PASSWORD) must not be blank");
        } else if (WEAK_DB_PASSWORDS.contains(dbPassword.toLowerCase())) {
            violations.add("spring.datasource.password (DB_PASSWORD) is a well-known default");
        }

        if (env.getProperty("stock.redis.enabled", Boolean.class, false)
                && env.getProperty("spring.data.redis.password", "").isBlank()) {
            violations.add("spring.data.redis.password (REDIS_PASSWORD) is required when stock.redis.enabled=true");
        }

        String provider = env.getProperty("payment.provider", "none");
        if ("sandbox".equalsIgnoreCase(provider)) {
            violations.add("payment.provider=sandbox lets buyers mark their own orders paid; use none or ecpay");
        } else if ("ecpay".equalsIgnoreCase(provider)) {
            for (String key : ECPAY_REQUIRED) {
                if (env.getProperty(key, "").isBlank()) {
                    violations.add(key + " is required when payment.provider=ecpay");
                }
            }
        }

        validateCorsOrigins(env.getProperty("cors.allowed-origins", ""), violations);
        return violations;
    }

    private static void validateCorsOrigins(String raw, List<String> violations) {
        String[] origins = raw.isBlank() ? new String[0] : raw.split(",");
        if (origins.length == 0) {
            violations.add("cors.allowed-origins (CORS_ALLOWED_ORIGINS) must list the public frontend origin");
            return;
        }
        for (String origin : origins) {
            if (!isPlainOrigin(origin.trim())) {
                violations.add("cors.allowed-origins (CORS_ALLOWED_ORIGINS) has an entry that is not a plain "
                        + "http(s) origin such as https://shop.example.com (wildcards and paths are rejected)");
                return;
            }
        }
    }

    private static boolean isPlainOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            boolean httpScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
            String path = uri.getPath();
            return httpScheme && uri.getHost() != null && !uri.getHost().contains("*")
                    && (path == null || path.isEmpty()) && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
