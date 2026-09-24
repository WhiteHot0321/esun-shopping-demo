package com.esun.shop.service;

import com.esun.shop.dto.PaymentRedirect;
import com.esun.shop.dto.PaymentView;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.PayStatus;
import com.esun.shop.model.PaymentResult;
import com.esun.shop.repository.PaymentRepository;
import com.esun.shop.repository.PaymentRepository.OrderPayState;
import com.esun.shop.repository.PaymentRepository.Payment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Buyer-facing payment operations. The amount is always the server-side order price (never client input) and the
 * payment identity comes from the verified principal. Whether an order is actually paid is decided only by
 * {@link PaymentCallbackService}.
 */
@Service
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentGateway gateway;
    private final PaymentCallbackService callbackService;

    public PaymentService(PaymentRepository paymentRepository, PaymentGateway gateway,
                          PaymentCallbackService callbackService) {
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.callbackService = callbackService;
    }

    /**
     * Opens (or returns the already-open) payment attempt for the caller's own order. Idempotent for double clicks:
     * the order row lock serialises callers and the DB allows at most one live attempt per order.
     */
    @Transactional
    public PaymentView start(String orderId, String memberId) {
        requireEnabled();
        OrderPayState order = paymentRepository.lockOrder(orderId)
                .filter(o -> o.memberId().equals(memberId))
                .orElseThrow(() -> new BusinessException("找不到訂單", HttpStatus.NOT_FOUND));
        if ("CANCELLED".equals(order.orderStatus())) {
            throw new BusinessException("訂單已取消，無法付款", HttpStatus.CONFLICT);
        }
        if (order.payStatus() != PayStatus.PENDING.ordinal()) {
            throw new BusinessException("訂單已付款", HttpStatus.CONFLICT);
        }
        if (order.price().signum() <= 0) {
            throw new BusinessException("訂單金額為 0，無需付款", HttpStatus.CONFLICT);
        }
        if (!gateway.canResumeAttempt()) {
            paymentRepository.closeOpenAttempts(orderId, "SUPERSEDED_BY_RETRY");
        }
        Payment payment = paymentRepository.findOpenAttempt(orderId).orElseGet(() -> {
            String tradeNo = gateway.newMerchantTradeNo();
            paymentRepository.insert(orderId, tradeNo, gateway.providerName(), order.price());
            return paymentRepository.findByTradeNo(tradeNo).orElseThrow();
        });
        // May refuse (e.g. a fractional TWD amount for ECPay): the exception rolls back the attempt inserted above.
        Optional<PaymentRedirect> redirect = gateway.checkout(payment.merchantTradeNo(), payment.amount(),
                "ESUN order " + orderId);
        return toView(payment, redirect.orElse(null));
    }

    /**
     * Sandbox stand-in for the provider's hosted payment page + server callback: reports {@code result} for the
     * caller's own attempt through the very same signed-callback path a real provider would use.
     */
    public PaymentView simulate(String merchantTradeNo, String memberId, PaymentResult result) {
        if (!gateway.supportsSimulation()) {
            throw new BusinessException("找不到資源", HttpStatus.NOT_FOUND);
        }
        Payment payment = paymentRepository.findByTradeNo(merchantTradeNo)
                .filter(p -> paymentRepository.findOrderOwner(p.orderId()).filter(memberId::equals).isPresent())
                .orElseThrow(() -> new BusinessException("找不到付款紀錄", HttpStatus.NOT_FOUND));
        String providerRef = "SBX-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        callbackService.handle(gateway.simulatedCallback(merchantTradeNo, payment.amount(), result, providerRef));
        return toView(paymentRepository.findByTradeNo(merchantTradeNo).orElseThrow(), null);
    }

    private void requireEnabled() {
        if (!gateway.isEnabled()) {
            throw new BusinessException("付款服務未啟用（開發環境請設定 PAYMENT_PROVIDER=sandbox）",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private PaymentView toView(Payment payment, PaymentRedirect redirect) {
        return new PaymentView(payment.id(), payment.orderId(), payment.merchantTradeNo(), payment.amount(),
                payment.status().name(), payment.provider(), gateway.supportsSimulation(), payment.createdAt(),
                payment.paidAt(), redirect);
    }
}
