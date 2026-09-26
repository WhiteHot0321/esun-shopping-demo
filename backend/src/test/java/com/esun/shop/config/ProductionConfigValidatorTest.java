package com.esun.shop.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.3 #18 B1: the prod profile must refuse to start on configuration that is unsafe on the public internet,
 * and must say which setting is wrong without echoing its value.
 */
class ProductionConfigValidatorTest {

    private static final String STRONG_SECRET = "x7Qp9-not-a-real-secret-0123456789abcdef";
    private static final String GOOD_ORIGIN = "https://shop.example.com";

    /** A configuration that passes every rule; each test breaks exactly one thing. */
    private static MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("jwt.secret", STRONG_SECRET)
                .withProperty("spring.datasource.password", "S3cure-db-pass-4f9a")
                .withProperty("payment.provider", "none")
                .withProperty("cors.allowed-origins", GOOD_ORIGIN);
    }

    private static List<String> violations(MockEnvironment env) {
        return ProductionConfigValidator.validate(env);
    }

    @Test
    void validConfiguration_hasNoViolations() {
        assertThat(violations(valid())).isEmpty();
    }

    @Test
    void jwtSecret_missingOrShort_isRejected() {
        assertThat(violations(valid().withProperty("jwt.secret", ""))).anyMatch(v -> v.contains("JWT_SECRET"));
        assertThat(violations(valid().withProperty("jwt.secret", "too-short"))).anyMatch(v -> v.contains("JWT_SECRET"));
    }

    @Test
    void jwtSecret_devOnlyValue_isRejectedEvenWhenLongEnough() {
        String devSecret = "dev-only-insecure-secret-change-me-please-32bytes";
        assertThat(violations(valid().withProperty("jwt.secret", devSecret)))
                .anyMatch(v -> v.contains("JWT_SECRET") && v.contains("dev-only"));
    }

    @Test
    void dbPassword_blankOrKnownDefault_isRejected() {
        assertThat(violations(valid().withProperty("spring.datasource.password", " ")))
                .anyMatch(v -> v.contains("DB_PASSWORD"));
        assertThat(violations(valid().withProperty("spring.datasource.password", "123456")))
                .anyMatch(v -> v.contains("DB_PASSWORD"));
    }

    @Test
    void redisPassword_requiredOnlyWhenStockRedisEnabled() {
        assertThat(violations(valid())).isEmpty();
        assertThat(violations(valid().withProperty("stock.redis.enabled", "true")))
                .anyMatch(v -> v.contains("REDIS_PASSWORD"));
        assertThat(violations(valid()
                .withProperty("stock.redis.enabled", "true")
                .withProperty("spring.data.redis.password", "redis-pass-9d2"))).isEmpty();
    }

    @Test
    void paymentSandbox_isRejected() {
        assertThat(violations(valid().withProperty("payment.provider", "sandbox")))
                .anyMatch(v -> v.contains("sandbox"));
    }

    @Test
    void ecpay_requiresEveryEcpaySetting() {
        List<String> found = violations(valid().withProperty("payment.provider", "ecpay"));
        assertThat(found).hasSize(6)
                .anyMatch(v -> v.contains("ecpay.hash-key"))
                .anyMatch(v -> v.contains("ecpay.callback-url"));

        MockEnvironment complete = valid().withProperty("payment.provider", "ecpay");
        for (String key : List.of("merchant-id", "hash-key", "hash-iv", "payment-url", "callback-url", "return-url")) {
            complete.withProperty("ecpay." + key, "configured");
        }
        assertThat(violations(complete)).isEmpty();
    }

    @Test
    void corsOrigins_missingWildcardOrMalformed_areRejected() {
        for (String bad : List.of("", "*", "https://*.example.com", "shop.example.com",
                "https://shop.example.com/app", "ftp://shop.example.com", "https://ok.example.com,*")) {
            assertThat(violations(valid().withProperty("cors.allowed-origins", bad)))
                    .as("origins [%s]", bad)
                    .anyMatch(v -> v.contains("CORS_ALLOWED_ORIGINS"));
        }
    }

    @Test
    void corsOrigins_multipleValidOrigins_areAccepted() {
        assertThat(violations(valid().withProperty("cors.allowed-origins",
                "https://shop.example.com, https://www.example.com:8443"))).isEmpty();
    }

    @Test
    void everyViolationIsReported_andNoValueIsEchoed() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("jwt.secret", "leaky-short-secret")
                .withProperty("spring.datasource.password", "123456")
                .withProperty("payment.provider", "sandbox")
                .withProperty("cors.allowed-origins", "*");

        List<String> found = violations(env);

        assertThat(found).hasSize(4);
        assertThat(String.join("\n", found)).doesNotContain("leaky-short-secret").doesNotContain("123456");
    }

    // --- wiring: the check really runs at startup, and only under the prod profile ---

    private ApplicationContextRunner runner(boolean prod) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ProductionConfigValidator.class)
                .withInitializer(ctx -> {
                    if (prod) {
                        ctx.getEnvironment().setActiveProfiles("prod");
                    }
                });
    }

    @Test
    void prodProfile_withUnsafeConfig_failsStartup_namingTheSetting() {
        runner(true).withPropertyValues("jwt.secret=short", "cors.allowed-origins=" + GOOD_ORIGIN,
                        "spring.datasource.password=S3cure-db-pass-4f9a")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause()
                            .hasMessageContaining("JWT_SECRET")
                            .hasMessageNotContaining("=short");
                });
    }

    @Test
    void prodProfile_withSafeConfig_starts() {
        runner(true).withPropertyValues("jwt.secret=" + STRONG_SECRET, "cors.allowed-origins=" + GOOD_ORIGIN,
                        "spring.datasource.password=S3cure-db-pass-4f9a")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ProductionConfigValidator.class));
    }

    @Test
    void nonProdProfile_doesNotApplyTheCheck() {
        runner(false).run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ProductionConfigValidator.class));
    }
}
