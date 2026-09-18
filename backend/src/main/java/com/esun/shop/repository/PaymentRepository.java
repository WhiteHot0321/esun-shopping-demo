package com.esun.shop.repository;

import com.esun.shop.model.PaymentTransaction;
import com.esun.shop.model.ShopOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class PaymentRepository {
    private final JdbcTemplate jdbcTemplate;

    public PaymentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ShopOrder> findOrder(String orderId) {
        String sql = "SELECT order_id, member_id, price, pay_status FROM shop_order WHERE order_id = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            ShopOrder order = new ShopOrder();
            order.setOrderId(rs.getString("order_id"));
            order.setMemberId(rs.getString("member_id"));
            order.setPrice(rs.getBigDecimal("price"));
            order.setPayStatus(rs.getInt("pay_status"));
            return order;
        }, orderId).stream().findFirst();
    }

    public Optional<String> findMerchantTradeNoByOrderId(String orderId) {
        return jdbcTemplate.query("SELECT merchant_trade_no FROM payment_transaction WHERE order_id = ?",
                (rs, rowNum) -> rs.getString(1), orderId).stream().findFirst();
    }

    public void createPaymentTransaction(String orderId, String merchantTradeNo) {
        jdbcTemplate.update("INSERT INTO payment_transaction(order_id, merchant_trade_no) VALUES (?, ?)",
                orderId, merchantTradeNo);
    }

    /** Locks both the payment row and its order so the callback state transition is serializable. */
    public Optional<PaymentTransaction> lockByMerchantTradeNo(String merchantTradeNo) {
        String sql = "SELECT pt.order_id, pt.merchant_trade_no, pt.provider_transaction_id, "
                + "so.member_id, so.price, so.pay_status "
                + "FROM payment_transaction pt JOIN shop_order so ON so.order_id = pt.order_id "
                + "WHERE pt.merchant_trade_no = ? FOR UPDATE";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            PaymentTransaction transaction = new PaymentTransaction();
            transaction.setOrderId(rs.getString("order_id"));
            transaction.setMerchantTradeNo(rs.getString("merchant_trade_no"));
            transaction.setProviderTransactionId(rs.getString("provider_transaction_id"));
            transaction.setMemberId(rs.getString("member_id"));
            transaction.setAmount(rs.getBigDecimal("price"));
            transaction.setPayStatus(rs.getInt("pay_status"));
            return transaction;
        }, merchantTradeNo).stream().findFirst();
    }

    public void recordProviderTransaction(String orderId, String providerTransactionId) {
        jdbcTemplate.update("UPDATE payment_transaction SET provider_transaction_id = ? WHERE order_id = ?",
                providerTransactionId, orderId);
    }

    public int markPaidIfPending(String orderId, int pendingStatus, int paidStatus) {
        return jdbcTemplate.update("UPDATE shop_order SET pay_status = ? WHERE order_id = ? AND pay_status = ?",
                paidStatus, orderId, pendingStatus);
    }
}
