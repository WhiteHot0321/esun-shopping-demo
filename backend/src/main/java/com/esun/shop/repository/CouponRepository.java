package com.esun.shop.repository;

import com.esun.shop.model.DiscountType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Coupon persistence. Lock order for every path that touches more than one coupon-related row is
 * coupon row -> coupon_member_usage row, and coupon rows are always taken before product rows (checkout) or right
 * after the order row and before product rows (cancel), so redemption can never deadlock stock handling.
 */
@Repository
public class CouponRepository {
    public record Coupon(long id, String code, DiscountType type, BigDecimal value, BigDecimal maxDiscount,
                         BigDecimal minOrderAmount, Integer totalQuota, int usedCount, int perMemberLimit,
                         LocalDateTime startsAt, LocalDateTime expiresAt, boolean active, String createdBy,
                         LocalDateTime createdAt) { }

    private static final String SELECT = """
            SELECT id, code, discount_type, discount_value, max_discount, min_order_amount, total_quota, used_count,
                   per_member_limit, starts_at, expires_at, active, created_by, created_at
            FROM coupon
            """;

    private static final RowMapper<Coupon> MAPPER = (rs, n) -> new Coupon(
            rs.getLong("id"), rs.getString("code"), DiscountType.valueOf(rs.getString("discount_type")),
            rs.getBigDecimal("discount_value"), rs.getBigDecimal("max_discount"), rs.getBigDecimal("min_order_amount"),
            (Integer) rs.getObject("total_quota"), rs.getInt("used_count"), rs.getInt("per_member_limit"),
            rs.getObject("starts_at", LocalDateTime.class), rs.getObject("expires_at", LocalDateTime.class),
            rs.getBoolean("active"), rs.getString("created_by"), rs.getObject("created_at", LocalDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public CouponRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Coupon> findByCode(String code) {
        return jdbcTemplate.query(SELECT + " WHERE code = ?", MAPPER, code).stream().findFirst();
    }

    /**
     * Current-read row lock. Locking by primary key (a row that is known to exist) rather than by code avoids taking a
     * gap lock on the unique index for codes that do not exist.
     */
    public Optional<Coupon> lockById(long id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }

    public long insert(Coupon coupon) {
        LocalDateTime now = LocalDateTime.now();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO coupon(code, discount_type, discount_value, "
                    + "max_discount, min_order_amount, total_quota, per_member_limit, starts_at, expires_at, active, "
                    + "created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, coupon.code());
            ps.setString(2, coupon.type().name());
            ps.setBigDecimal(3, coupon.value());
            ps.setBigDecimal(4, coupon.maxDiscount());
            ps.setBigDecimal(5, coupon.minOrderAmount());
            ps.setObject(6, coupon.totalQuota());
            ps.setInt(7, coupon.perMemberLimit());
            ps.setObject(8, coupon.startsAt());
            ps.setObject(9, coupon.expiresAt());
            ps.setBoolean(10, coupon.active());
            ps.setString(11, coupon.createdBy());
            // App-written like every other timestamp compared in Java, so a DB session time zone cannot skew them.
            ps.setObject(12, now);
            ps.setObject(13, now);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void updateMutable(long id, boolean active, LocalDateTime expiresAt, Integer totalQuota) {
        jdbcTemplate.update("UPDATE coupon SET active = ?, expires_at = ?, total_quota = ?, updated_at = ? WHERE id = ?",
                active, expiresAt, totalQuota, LocalDateTime.now(), id);
    }

    public void updateActive(long id, boolean active) {
        jdbcTemplate.update("UPDATE coupon SET active = ?, updated_at = ? WHERE id = ?", active, LocalDateTime.now(), id);
    }

    public List<Coupon> list(int limit, int offset) {
        return jdbcTemplate.query(SELECT + " ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, limit, offset);
    }

    public long count() {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon", Long.class);
        return total == null ? 0 : total;
    }

    /** Caller must hold the coupon row lock; the CHECK constraint on quota is only a backstop. */
    public void incrementUsed(long couponId) {
        jdbcTemplate.update("UPDATE coupon SET used_count = used_count + 1 WHERE id = ?", couponId);
    }

    public void decrementUsed(long couponId) {
        jdbcTemplate.update("UPDATE coupon SET used_count = used_count - 1 WHERE id = ? AND used_count > 0", couponId);
    }

    /**
     * Atomically takes one per-member redemption slot. INSERT IGNORE creates the row (no gap-lock-then-insert
     * pattern), then the conditional UPDATE is a current read: it matches only while the member is under the limit.
     */
    public boolean tryIncrementMemberUsage(long couponId, String memberId, int limit) {
        jdbcTemplate.update("INSERT IGNORE INTO coupon_member_usage(coupon_id, member_id, used_count) VALUES (?, ?, 0)",
                couponId, memberId);
        return jdbcTemplate.update("UPDATE coupon_member_usage SET used_count = used_count + 1 "
                + "WHERE coupon_id = ? AND member_id = ? AND used_count < ?", couponId, memberId, limit) == 1;
    }

    public void decrementMemberUsage(long couponId, String memberId) {
        jdbcTemplate.update("UPDATE coupon_member_usage SET used_count = used_count - 1 "
                + "WHERE coupon_id = ? AND member_id = ? AND used_count > 0", couponId, memberId);
    }

    /** Non-locking read for advisory previews only. */
    public int memberUsage(long couponId, String memberId) {
        List<Integer> rows = jdbcTemplate.queryForList("SELECT used_count FROM coupon_member_usage "
                + "WHERE coupon_id = ? AND member_id = ?", Integer.class, couponId, memberId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }
}
