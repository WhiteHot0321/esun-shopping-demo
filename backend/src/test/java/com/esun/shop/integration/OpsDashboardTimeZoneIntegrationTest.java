package com.esun.shop.integration;

import com.esun.shop.model.ShopOrder;
import com.esun.shop.repository.OrderRepository;
import com.esun.shop.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract behind the admin dashboard's "today" window (docs/tasks/075, D4 option a). The dashboard converts the
 * Asia/Taipei day into {@code ops.dashboard.storage-zone} wall-clock before querying, which is only correct while the
 * database really stores that zone. {@code shop_order.created_at} is written by the DB default (MySQL server zone) and
 * the payment columns by {@code LocalDateTime.now()} (JVM zone); neither the stock {@code mysql:8.0} image nor the
 * backend image (no TZ / user.timezone) is set to Taipei, so both hold UTC. If either side is ever reconfigured, this
 * fails and the dashboard's counts would otherwise silently shift by hours.
 *
 * The JVM zone is pinned to UTC to match the backend container, not the developer machine. Raw values are read with
 * {@code CAST(... AS CHAR)} so the JDBC driver's own zone handling cannot hide what the database actually holds.
 */
class OpsDashboardTimeZoneIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final DateTimeFormatter RAW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired JdbcTemplate jdbc;
    @Value("${ops.dashboard.storage-zone:UTC}") String storageZone;

    private TimeZone originalJvmZone;
    private String orderId;

    @BeforeEach
    void insertOrderUnderProductionLikeJvmZone() {
        originalJvmZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        orderId = "TZ-" + UUID.randomUUID().toString().substring(0, 12);
        ShopOrder order = new ShopOrder();
        order.setOrderId(orderId);
        order.setMemberId("tz-test@example.com");
        order.setPrice(new BigDecimal("100.00"));
        order.setPayStatus(0);
        orderRepository.insertOrder(order, null);
    }

    @AfterEach
    void cleanUp() {
        TimeZone.setDefault(originalJvmZone);
        jdbc.update("DELETE FROM payment WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM shop_order WHERE order_id = ?", orderId);
    }

    @Test
    void shopOrderCreatedAtHoldsConfiguredStorageZoneWallClock() {
        LocalDateTime stored = raw("SELECT CAST(created_at AS CHAR) FROM shop_order WHERE order_id = ?");

        assertWithinTolerance("shop_order.created_at", stored);
    }

    @Test
    void paymentTimestampsHoldConfiguredStorageZoneWallClock() {
        paymentRepository.insert(orderId, "TZ" + orderId, "TEST", new BigDecimal("100.00"));

        LocalDateTime stored = raw("SELECT CAST(created_at AS CHAR) FROM payment WHERE order_id = ?");

        assertWithinTolerance("payment.created_at", stored);
    }

    private LocalDateTime raw(String sql) {
        String value = jdbc.queryForObject(sql, String.class, orderId);
        return LocalDateTime.parse(value, RAW);
    }

    private void assertWithinTolerance(String column, LocalDateTime stored) {
        LocalDateTime expected = LocalDateTime.now(ZoneId.of(storageZone));
        Duration drift = Duration.between(stored, expected).abs();
        assertThat(drift)
                .as("%s stored %s but %s wall-clock now is %s (drift %s); dashboard 'today' would shift",
                        column, stored, storageZone, expected, drift)
                .isLessThanOrEqualTo(TOLERANCE);
    }
}
