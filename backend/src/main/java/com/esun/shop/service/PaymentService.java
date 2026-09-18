package com.esun.shop.service;

import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.PayStatus;
import com.esun.shop.model.PaymentTransaction;
import com.esun.shop.model.ShopOrder;
import com.esun.shop.payment.PaymentForm;
import com.esun.shop.payment.PaymentGateway;
import com.esun.shop.payment.VerifiedPaymentCallback;
import com.esun.shop.repository.PaymentRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

@Service
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;

    public PaymentService(PaymentRepository paymentRepository, PaymentGateway paymentGateway) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
    }

    @Transactional
    public PaymentForm createPaymentForm(String orderId, String authenticatedEmail) {
        ShopOrder order = paymentRepository.findOrder(orderId)
                .orElseThrow(() -> new BusinessException("訂單不存在", HttpStatus.NOT_FOUND));
        // The JWT subject is the member identity. Existing unaffiliated legacy member IDs cannot
        // use this endpoint until their order ownership is migrated to that identity.
        if (!order.getMemberId().equals(authenticatedEmail)) {
            throw new BusinessException("無權存取此訂單付款", HttpStatus.FORBIDDEN);
        }
        if (order.getPayStatus() != PayStatus.PENDING.ordinal()) {
            throw new BusinessException("只有待付款訂單可建立付款表單", HttpStatus.CONFLICT);
        }
        String merchantTradeNo = paymentRepository.findMerchantTradeNoByOrderId(orderId).orElseGet(() -> {
            String generated = merchantTradeNo(orderId);
            try {
                paymentRepository.createPaymentTransaction(orderId, generated);
                return generated;
            } catch (DuplicateKeyException ex) {
                return paymentRepository.findMerchantTradeNoByOrderId(orderId).orElseThrow(() -> ex);
            }
        });
        return paymentGateway.createPaymentForm(merchantTradeNo, order.getPrice(), "ESUN order " + orderId);
    }

    /** @return true for the first paid transition and false for a signed, unsuccessful or duplicate callback. */
    @Transactional
    public boolean processEcpayCallback(Map<String, String> parameters) {
        VerifiedPaymentCallback callback = paymentGateway.verifyCallback(parameters)
                .orElseThrow(() -> new BusinessException("付款通知驗證失敗", HttpStatus.BAD_REQUEST));
        if (!paymentGateway.merchantId().equals(callback.merchantId())) {
            throw new BusinessException("付款通知商店編號不符", HttpStatus.BAD_REQUEST);
        }
        if (!callback.successful()) return false;

        PaymentTransaction transaction = paymentRepository.lockByMerchantTradeNo(callback.merchantTradeNo())
                .orElseThrow(() -> new BusinessException("付款通知訂單不存在", HttpStatus.BAD_REQUEST));
        if (transaction.getAmount().compareTo(callback.amount()) != 0) {
            throw new BusinessException("付款通知金額不符", HttpStatus.BAD_REQUEST);
        }
        if (transaction.getPayStatus() == PayStatus.PAID.ordinal()) {
            if (callback.providerTransactionId().equals(transaction.getProviderTransactionId())) return false;
            throw new BusinessException("付款通知交易編號衝突", HttpStatus.CONFLICT);
        }
        if (transaction.getPayStatus() != PayStatus.PENDING.ordinal()) {
            throw new BusinessException("訂單付款狀態不允許更新", HttpStatus.CONFLICT);
        }
        if (transaction.getProviderTransactionId() != null
                && !callback.providerTransactionId().equals(transaction.getProviderTransactionId())) {
            throw new BusinessException("付款通知交易編號衝突", HttpStatus.CONFLICT);
        }
        paymentRepository.recordProviderTransaction(transaction.getOrderId(), callback.providerTransactionId());
        if (paymentRepository.markPaidIfPending(transaction.getOrderId(), PayStatus.PENDING.ordinal(), PayStatus.PAID.ordinal()) != 1) {
            throw new BusinessException("訂單付款狀態更新失敗", HttpStatus.CONFLICT);
        }
        return true;
    }

    private static String merchantTradeNo(String orderId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(orderId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : hash) hex.append(String.format("%02X", value));
            return "E" + hex.substring(0, 19); // E + 19 hex characters: ECPay's 20-char alphanumeric limit.
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
