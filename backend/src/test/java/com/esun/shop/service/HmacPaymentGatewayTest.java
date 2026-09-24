package com.esun.shop.service;

import com.esun.shop.model.PaymentResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests for the callback signature: any change to any signed field must invalidate it. */
class HmacPaymentGatewayTest {
    private static final String SECRET = "unit-test-payment-secret-0123456789";
    private final HmacPaymentGateway gateway = new HmacPaymentGateway(SECRET);

    private String sign() {
        return gateway.sign("PAYabc", new BigDecimal("199.00"), PaymentResult.SUCCESS, "REF-1");
    }

    @Test
    void validSignatureVerifies() {
        assertThat(gateway.verify("PAYabc", new BigDecimal("199.00"), PaymentResult.SUCCESS, "REF-1", sign())).isTrue();
    }

    @Test
    void amountIsCanonicalisedSoScaleDoesNotMatter() {
        assertThat(gateway.verify("PAYabc", new BigDecimal("199"), PaymentResult.SUCCESS, "REF-1", sign())).isTrue();
        assertThat(gateway.verify("PAYabc", new BigDecimal("199.0"), PaymentResult.SUCCESS, "REF-1", sign())).isTrue();
    }

    @Test
    void tamperingWithAnySignedFieldInvalidatesTheSignature() {
        String signature = sign();
        assertThat(gateway.verify("PAYabd", new BigDecimal("199.00"), PaymentResult.SUCCESS, "REF-1", signature)).isFalse();
        assertThat(gateway.verify("PAYabc", new BigDecimal("1.00"), PaymentResult.SUCCESS, "REF-1", signature)).isFalse();
        assertThat(gateway.verify("PAYabc", new BigDecimal("199.00"), PaymentResult.FAILED, "REF-1", signature)).isFalse();
        assertThat(gateway.verify("PAYabc", new BigDecimal("199.00"), PaymentResult.SUCCESS, "REF-2", signature)).isFalse();
        assertThat(gateway.verify("PAYabc", new BigDecimal("199.00"), PaymentResult.SUCCESS, null, signature)).isFalse();
    }

    @Test
    void aDifferentSecretProducesADifferentSignature() {
        HmacPaymentGateway other = new HmacPaymentGateway("another-payment-secret-987654321");
        String forged = other.sign("PAYabc", new BigDecimal("199.00"), PaymentResult.SUCCESS, "REF-1");
        assertThat(gateway.verify("PAYabc", new BigDecimal("199.00"), PaymentResult.SUCCESS, "REF-1", forged)).isFalse();
    }

    @Test
    void malformedInputIsRejectedWithoutThrowing() {
        assertThat(gateway.verify(null, new BigDecimal("1"), PaymentResult.SUCCESS, null, "x")).isFalse();
        assertThat(gateway.verify("PAYabc", null, PaymentResult.SUCCESS, null, "x")).isFalse();
        assertThat(gateway.verify("PAYabc", new BigDecimal("1"), null, null, "x")).isFalse();
        assertThat(gateway.verify("PAYabc", new BigDecimal("1"), PaymentResult.SUCCESS, null, null)).isFalse();
        // more than two decimals cannot be canonicalised, so it can never have been signed
        assertThat(gateway.verify("PAYabc", new BigDecimal("1.005"), PaymentResult.SUCCESS, null, "x")).isFalse();
        // hostile magnitudes/signs are refused before any arithmetic is done on them
        assertThat(gateway.verify("PAYabc", new BigDecimal("1E+100000000"), PaymentResult.SUCCESS, null, "x")).isFalse();
        assertThat(gateway.verify("PAYabc", new BigDecimal("-1"), PaymentResult.SUCCESS, null, "x")).isFalse();
        assertThat(gateway.verify("PAYabc", BigDecimal.ZERO, PaymentResult.SUCCESS, null, "x")).isFalse();
        assertThat(gateway.verify("PAYabc", new BigDecimal("12345678901234567890"), PaymentResult.SUCCESS, null, "x")).isFalse();
        // the field separator must not be smuggled into signed fields
        assertThat(gateway.verify("PAY|abc", new BigDecimal("1"), PaymentResult.SUCCESS, null, "x")).isFalse();
        assertThat(gateway.verify("PAYabc", new BigDecimal("1"), PaymentResult.SUCCESS, "a|b", "x")).isFalse();
    }

    @Test
    void signatureComparisonIsCaseInsensitiveHexButNotSubstringMatching() {
        String signature = sign();
        assertThat(gateway.verify("PAYabc", new BigDecimal("199.00"), PaymentResult.SUCCESS, "REF-1",
                signature.toUpperCase())).isTrue();
        assertThat(gateway.verify("PAYabc", new BigDecimal("199.00"), PaymentResult.SUCCESS, "REF-1",
                signature.substring(0, 32))).isFalse();
    }

    @Test
    void shortSecretsAreRefusedAtStartup() {
        assertThatThrownBy(() -> new HmacPaymentGateway("short")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HmacPaymentGateway(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void verifyCallbackAcceptsOnlyAWellFormedSignedParameterSet() {
        var params = gateway.simulatedCallback("PAYabc", new BigDecimal("199"), PaymentResult.SUCCESS, "REF-1");
        assertThat(gateway.verifyCallback(params)).hasValueSatisfying(callback -> {
            assertThat(callback.merchantTradeNo()).isEqualTo("PAYabc");
            assertThat(callback.amount()).isEqualByComparingTo("199");
            assertThat(callback.result()).isEqualTo(PaymentResult.SUCCESS);
            assertThat(callback.providerRef()).isEqualTo("REF-1");
        });
        var tampered = new java.util.LinkedHashMap<>(params);
        tampered.put("result", "FAILED");
        assertThat(gateway.verifyCallback(tampered)).isEmpty();
        var garbage = new java.util.LinkedHashMap<>(params);
        garbage.put("amount", "not-a-number");
        assertThat(gateway.verifyCallback(garbage)).isEmpty();
        garbage.put("amount", "199.00");
        garbage.put("result", "MAYBE");
        assertThat(gateway.verifyCallback(garbage)).isEmpty();
        assertThat(gateway.verifyCallback(java.util.Map.of())).isEmpty();
    }

    @Test
    void sandboxProviderIsEnabledAndSimulatable() {
        assertThat(gateway.isEnabled()).isTrue();
        assertThat(gateway.supportsSimulation()).isTrue();
        assertThat(gateway.checkout("PAYabc", new BigDecimal("1"), "x")).isEmpty();
    }
}
