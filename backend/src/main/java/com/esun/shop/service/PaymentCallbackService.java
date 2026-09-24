package com.esun.shop.service;

import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.PayStatus;
import com.esun.shop.model.PaymentResult;
import com.esun.shop.model.PaymentStatus;
import com.esun.shop.repository.PaymentRepository;
import com.esun.shop.repository.PaymentRepository.OrderPayState;
import com.esun.shop.repository.PaymentRepository.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The only place an order becomes PAID. A callback is trusted only after its signature verifies; its effect is then
 * applied in one transaction (opened only after authentication, so unauthenticated floods never hold a connection)
 * that row-locks the order first and the payment second (the same order as cancel and
 * checkout) and compare-and-sets both, so:
 * <ul>
 *   <li>replays and concurrent duplicates change nothing (idempotent),</li>
 *   <li>a callback racing a buyer cancellation ends in exactly one consistent state (REFUND_REQUIRED),</li>
 *   <li>money that cannot be applied to the order is recorded, never silently dropped or double-counted.</li>
 * </ul>
 */
@Service
public class PaymentCallbackService {
    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackService.class);

    public enum Outcome { SUCCEEDED, FAILED, DUPLICATE, REFUND_REQUIRED }

    private final PaymentGateway gateway;
    private final PaymentRepository paymentRepository;
    private final TransactionTemplate transactionTemplate;

    public PaymentCallbackService(PaymentGateway gateway, PaymentRepository paymentRepository,
                                  TransactionTemplate transactionTemplate) {
        this.gateway = gateway;
        this.paymentRepository = paymentRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /** @param parameters the raw provider callback parameters; only the gateway knows how to authenticate them */
    public Outcome handle(Map<String, String> parameters) {
        if (!gateway.isEnabled()) {
            throw new BusinessException("付款服務未啟用", HttpStatus.SERVICE_UNAVAILABLE);
        }
        // Authenticate before touching the database, so an unauthenticated caller learns nothing about which trade
        // numbers exist.
        VerifiedPaymentCallback callback = gateway.verifyCallback(parameters).orElseThrow(() -> {
            log.warn("Rejected payment callback that failed verification");
            return new BusinessException("簽章驗證失敗", HttpStatus.BAD_REQUEST);
        });
        return transactionTemplate.execute(status -> apply(callback.merchantTradeNo(), callback.amount(),
                callback.result(), callback.providerRef()));
    }

    private Outcome apply(String merchantTradeNo, BigDecimal amount, PaymentResult result, String providerRef) {
        Payment unlocked = paymentRepository.findByTradeNo(merchantTradeNo)
                .orElseThrow(() -> new BusinessException("找不到付款紀錄", HttpStatus.NOT_FOUND));
        OrderPayState order = paymentRepository.lockOrder(unlocked.orderId())
                .orElseThrow(() -> new BusinessException("找不到訂單", HttpStatus.NOT_FOUND));
        Payment payment = paymentRepository.lockById(unlocked.id())
                .orElseThrow(() -> new BusinessException("找不到付款紀錄", HttpStatus.NOT_FOUND));

        if (amount.compareTo(payment.amount()) != 0) {
            log.error("Payment callback amount mismatch, tradeNo={}, expected={}, got={}", merchantTradeNo,
                    payment.amount(), amount);
            // Authentic callback reporting money moved for a different amount: never apply it to the order, but keep
            // the trace instead of dropping it, so it can be reconciled.
            if (result == PaymentResult.SUCCESS
                    && (payment.status() == PaymentStatus.INITIATED || payment.status() == PaymentStatus.FAILED)) {
                return parkForRefund(payment, payment.status(), order, "AMOUNT_MISMATCH", providerRef);
            }
            throw new BusinessException("付款金額與訂單不符", HttpStatus.BAD_REQUEST);
        }

        return result == PaymentResult.SUCCESS
                ? applySuccess(payment, order, providerRef)
                : applyFailure(payment, providerRef);
    }

    private Outcome applySuccess(Payment payment, OrderPayState order, String providerRef) {
        return switch (payment.status()) {
            case SUCCEEDED, REFUND_REQUIRED -> Outcome.DUPLICATE;
            case INITIATED -> settle(payment, PaymentStatus.INITIATED, order, providerRef, "ORDER_NOT_PAYABLE");
            // We had closed this attempt (a decline, a "pay again", or a cancelled order) and the provider now says
            // money moved: if the order is still waiting for payment the buyer paid, so apply it.
            case FAILED -> settle(payment, PaymentStatus.FAILED, order, providerRef, "LATE_SUCCESS_ON_CLOSED_ATTEMPT");
        };
    }

    /** Apply a success to a payable order, otherwise record the money as needing a refund. */
    private Outcome settle(Payment payment, PaymentStatus from, OrderPayState order, String providerRef,
                           String refundReason) {
        boolean orderCanTakePayment = !"CANCELLED".equals(order.orderStatus())
                && order.payStatus() == PayStatus.PENDING.ordinal();
        if (!orderCanTakePayment) {
            return parkForRefund(payment, from, order, refundReason, providerRef);
        }
        if (from == PaymentStatus.FAILED) {
            // A newer live attempt may exist; close it first so the one-live-attempt constraint holds.
            paymentRepository.closeOpenAttempts(order.orderId(), "SUPERSEDED_BY_LATE_SUCCESS");
        }
        require(paymentRepository.transition(payment.id(), from, PaymentStatus.SUCCEEDED, null, true, providerRef) == 1);
        require(paymentRepository.markOrderPaid(order.orderId()) == 1);
        log.info("Order {} paid via payment {}", order.orderId(), payment.merchantTradeNo());
        return Outcome.SUCCEEDED;
    }

    private Outcome applyFailure(Payment payment, String providerRef) {
        if (payment.status() != PaymentStatus.INITIATED) {
            // Already settled either way (a success followed by a stale failure must never undo the payment).
            return Outcome.DUPLICATE;
        }
        require(paymentRepository.transition(payment.id(), PaymentStatus.INITIATED, PaymentStatus.FAILED,
                "PROVIDER_DECLINED", false, providerRef) == 1);
        return Outcome.FAILED;
    }

    private Outcome parkForRefund(Payment payment, PaymentStatus from, OrderPayState order, String reason,
                                  String providerRef) {
        require(paymentRepository.transition(payment.id(), from, PaymentStatus.REFUND_REQUIRED, reason, false,
                providerRef) == 1);
        log.error("Payment {} for order {} needs a refund: {}", payment.merchantTradeNo(), order.orderId(), reason);
        return Outcome.REFUND_REQUIRED;
    }

    /** Rows are locked, so a failed compare-and-set means a bug; abort the whole transaction rather than half-apply. */
    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("payment state changed under lock");
    }
}
