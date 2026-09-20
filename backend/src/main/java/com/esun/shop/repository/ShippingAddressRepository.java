package com.esun.shop.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class ShippingAddressRepository {
    public record ShippingAddress(
            long id, String label, String receiverName, String phone,
            String postalCode, String address, boolean isDefault) {}

    private static final RowMapper<ShippingAddress> ROW_MAPPER = (rs, rowNum) -> new ShippingAddress(
            rs.getLong("id"), rs.getString("label"), rs.getString("receiver_name"),
            rs.getString("phone"), rs.getString("postal_code"), rs.getString("address"),
            rs.getBoolean("is_default"));

    private final JdbcTemplate jdbcTemplate;

    public ShippingAddressRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ShippingAddress> findAllByMemberEmail(String email) {
        return jdbcTemplate.query("""
                SELECT a.id, a.label, a.receiver_name, a.phone, a.postal_code, a.address, a.is_default
                FROM shipping_address a JOIN member m ON m.id = a.member_id
                WHERE m.email = ? ORDER BY a.is_default DESC, a.id
                """, ROW_MAPPER, email);
    }

    public Optional<ShippingAddress> findByIdAndMemberEmail(long id, String email) {
        return jdbcTemplate.query("""
                SELECT a.id, a.label, a.receiver_name, a.phone, a.postal_code, a.address, a.is_default
                FROM shipping_address a JOIN member m ON m.id = a.member_id
                WHERE a.id = ? AND m.email = ?
                """, ROW_MAPPER, id, email).stream().findFirst();
    }

    public Optional<ShippingAddress> findDefaultByMemberEmail(String email) {
        return jdbcTemplate.query("""
                SELECT a.id, a.label, a.receiver_name, a.phone, a.postal_code, a.address, a.is_default
                FROM shipping_address a JOIN member m ON m.id = a.member_id
                WHERE m.email = ? AND a.is_default = TRUE
                """, ROW_MAPPER, email).stream().findFirst();
    }

    public boolean hasAny(String email) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM shipping_address a JOIN member m ON m.id = a.member_id
                WHERE m.email = ?
                """, Integer.class, email);
        return count != null && count > 0;
    }

    public long insert(String email, String label, String receiverName, String phone,
            String postalCode, String address, boolean isDefault) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO shipping_address(member_id, label, receiver_name, phone, postal_code, address, is_default)
                    SELECT id, ?, ?, ?, ?, ?, ? FROM member WHERE email = ?
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, label);
            ps.setString(2, receiverName);
            ps.setString(3, phone);
            ps.setString(4, postalCode);
            ps.setString(5, address);
            ps.setBoolean(6, isDefault);
            ps.setString(7, email);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    public int update(long id, String email, String label, String receiverName,
            String phone, String postalCode, String address) {
        return jdbcTemplate.update("""
                UPDATE shipping_address a JOIN member m ON m.id = a.member_id
                SET a.label = ?, a.receiver_name = ?, a.phone = ?, a.postal_code = ?, a.address = ?
                WHERE a.id = ? AND m.email = ?
                """, label, receiverName, phone, postalCode, address, id, email);
    }

    public int clearDefault(String email) {
        return jdbcTemplate.update("""
                UPDATE shipping_address a JOIN member m ON m.id = a.member_id
                SET a.is_default = FALSE WHERE m.email = ? AND a.is_default = TRUE
                """, email);
    }

    public int markDefault(long id, String email) {
        return jdbcTemplate.update("""
                UPDATE shipping_address a JOIN member m ON m.id = a.member_id
                SET a.is_default = TRUE WHERE a.id = ? AND m.email = ?
                """, id, email);
    }

    public boolean isUsedByOrder(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_order WHERE shipping_address_id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    public int delete(long id, String email) {
        return jdbcTemplate.update("""
                DELETE a FROM shipping_address a JOIN member m ON m.id = a.member_id
                WHERE a.id = ? AND m.email = ?
                """, id, email);
    }

    public Long firstAddressId(String email) {
        return jdbcTemplate.query("""
                SELECT a.id FROM shipping_address a JOIN member m ON m.id = a.member_id
                WHERE m.email = ? ORDER BY a.id LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), email).stream().findFirst().orElse(null);
    }
}
