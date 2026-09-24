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

    public Outcome handle(String merchantTradeNo, BigDecimal amount, PaymentResult result, String providerRef,
                          String signature) {
        if (!gateway.isEnabled()) {
            throw new BusinessException("付款服務未啟用", HttpStatus.SERVICE_UNAVAILABLE);
        }
        // Authenticate before touching the database, so an unauthenticated caller learns nothing about which trade
        // numbers exist.
        if (!gateway.verify(merchantTradeNo, amount, result, providerRef, signature)) {
            log.warn("Rejected payment callback with invalid signature, tradeNo={}", merchantTradeNo);
            throw new BusinessException("簽章驗證失敗", HttpStatus.BAD_REQUEST);
        }
        return transactionTemplate.execute(status -> apply(merchantTradeNo, amount, result, providerRef));
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
            case INITIATED -> {
                boolean orderCanTakePayment = !"CANCELLED".equals(order.orderStatus())
                        && order.payStatus() == PayStatus.PENDING.ordinal();
                if (orderCanTakePayment) {
                    require(paymentRepository.transition(payment.id(), PaymentStatus.INITIATED, PaymentStatus.SUCCEEDED,
                            null, true, providerRef) == 1);
                    require(paymentRepository.markOrderPaid(order.orderId()) == 1);
                    log.info("Order {} paid via payment {}", order.orderId(), payment.merchantTradeNo());
                    yield Outcome.SUCCEEDED;
                }
                yield parkForRefund(payment, PaymentStatus.INITIATED, order, "ORDER_NOT_PAYABLE", providerRef);
            }
            // We had already closed this attempt (failure or order cancelled) and the provider now says money moved.
            case FAILED -> parkForRefund(payment, PaymentStatus.FAILED, order, "LATE_SUCCESS_ON_CLOSED_ATTEMPT",
                    providerRef);
        };
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
