package com.esun.shop.service;

import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.PayStatus;
import com.esun.shop.model.PaymentTransaction;
import com.esun.shop.model.ShopOrder;
import com.esun.shop.payment.PaymentForm;
import com.esun.shop.payment.PaymentGateway;
import com.esun.shop.payment.VerifiedPaymentCallback;
import com.esun.shop.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentGateway paymentGateway;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, paymentGateway);
        lenient().when(paymentGateway.merchantId()).thenReturn("3002607");
    }

    @Test
    void createPaymentForm_requiresOwnerAndLoadsAmountFromDatabase() {
        ShopOrder order = order("ORDER-1", "member@example.com", "100", PayStatus.PENDING);
        when(paymentRepository.findOrder("ORDER-1")).thenReturn(Optional.of(order));
        when(paymentRepository.findMerchantTradeNoByOrderId("ORDER-1")).thenReturn(Optional.of("E0123456789ABCDEF012"));
        PaymentForm form = new PaymentForm("https://provider.example", Map.of("TotalAmount", "100"));
        when(paymentGateway.createPaymentForm(anyString(), any(), anyString())).thenReturn(form);

        assertThat(paymentService.createPaymentForm("ORDER-1", "member@example.com")).isEqualTo(form);
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(paymentGateway).createPaymentForm(eq("E0123456789ABCDEF012"), amount.capture(), anyString());
        assertThat(amount.getValue()).isEqualByComparingTo("100");

        assertThatThrownBy(() -> paymentService.createPaymentForm("ORDER-1", "other@example.com"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void processCallback_firstSuccessfulCallbackRecordsProviderTransactionAndMarksPaid() {
        PaymentTransaction transaction = transaction("ORDER-1", "100", PayStatus.PENDING, null);
        when(paymentGateway.verifyCallback(any())).thenReturn(Optional.of(callback("100", "TX-1", true)));
        when(paymentRepository.lockByMerchantTradeNo("E0123456789ABCDEF012")).thenReturn(Optional.of(transaction));
        when(paymentRepository.markPaidIfPending("ORDER-1", PayStatus.PENDING.ordinal(), PayStatus.PAID.ordinal())).thenReturn(1);

        assertThat(paymentService.processEcpayCallback(Map.of("ignored", "input"))).isTrue();
        verify(paymentRepository).recordProviderTransaction("ORDER-1", "TX-1");
        verify(paymentRepository).markPaidIfPending("ORDER-1", PayStatus.PENDING.ordinal(), PayStatus.PAID.ordinal());
    }

    @Test
    void processCallback_duplicateSuccessfulCallbackIsIdempotent() {
        PaymentTransaction transaction = transaction("ORDER-1", "100", PayStatus.PAID, "TX-1");
        when(paymentGateway.verifyCallback(any())).thenReturn(Optional.of(callback("100", "TX-1", true)));
        when(paymentRepository.lockByMerchantTradeNo("E0123456789ABCDEF012")).thenReturn(Optional.of(transaction));

        assertThat(paymentService.processEcpayCallback(Map.of())).isFalse();
        verify(paymentRepository, never()).recordProviderTransaction(anyString(), anyString());
        verify(paymentRepository, never()).markPaidIfPending(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void processCallback_wrongAmountOrUnknownOrderCannotUpdateOrder() {
        when(paymentGateway.verifyCallback(any())).thenReturn(Optional.of(callback("99", "TX-1", true)));
        when(paymentRepository.lockByMerchantTradeNo("E0123456789ABCDEF012"))
                .thenReturn(Optional.of(transaction("ORDER-1", "100", PayStatus.PENDING, null)));
        assertThatThrownBy(() -> paymentService.processEcpayCallback(Map.of()))
                .isInstanceOf(BusinessException.class);
        verify(paymentRepository, never()).markPaidIfPending(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());

        when(paymentGateway.verifyCallback(any())).thenReturn(Optional.of(callback("100", "TX-1", true)));
        when(paymentRepository.lockByMerchantTradeNo("E0123456789ABCDEF012")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> paymentService.processEcpayCallback(Map.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void processCallback_unsignedOrUnsuccessfulCallbackCannotUpdateOrder() {
        when(paymentGateway.verifyCallback(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> paymentService.processEcpayCallback(Map.of()))
                .isInstanceOf(BusinessException.class);

        when(paymentGateway.verifyCallback(any())).thenReturn(Optional.of(callback("100", "TX-1", false)));
        assertThat(paymentService.processEcpayCallback(Map.of())).isFalse();
        verify(paymentRepository, never()).markPaidIfPending(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    private static ShopOrder order(String orderId, String memberId, String amount, PayStatus status) {
        ShopOrder order = new ShopOrder();
        order.setOrderId(orderId); order.setMemberId(memberId); order.setPrice(new BigDecimal(amount));
        order.setPayStatus(status.ordinal());
        return order;
    }

    private static PaymentTransaction transaction(String orderId, String amount, PayStatus status, String providerTransactionId) {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setOrderId(orderId); transaction.setMerchantTradeNo("E0123456789ABCDEF012");
        transaction.setAmount(new BigDecimal(amount)); transaction.setPayStatus(status.ordinal());
        transaction.setProviderTransactionId(providerTransactionId);
        return transaction;
    }

    private static VerifiedPaymentCallback callback(String amount, String transactionId, boolean successful) {
        return new VerifiedPaymentCallback("3002607", "E0123456789ABCDEF012", new BigDecimal(amount), transactionId, successful);
    }
}
