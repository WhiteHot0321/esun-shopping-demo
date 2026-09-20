package com.esun.shop.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class CartRepository {
    public record CartItem(long id, String productId, String productName, BigDecimal price,
            int stock, int quantity, LocalDateTime addedAt) {}

    private static final RowMapper<CartItem> ITEM_MAPPER = (rs, rowNum) -> new CartItem(
            rs.getLong("id"), rs.getString("product_id"), rs.getString("product_name"),
            rs.getBigDecimal("price"), rs.getInt("stock"), rs.getInt("cart_quantity"),
            rs.getTimestamp("added_at").toLocalDateTime());

    private static final String ITEM_SELECT = """
            SELECT c.id, c.product_id, p.product_name, p.price, p.quantity AS stock,
                   c.quantity AS cart_quantity, c.added_at
            FROM shopping_cart c
            JOIN member m ON m.id = c.member_id
            JOIN product p ON p.product_id = c.product_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public CartRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long lockMember(String email) {
        return jdbcTemplate.query("SELECT id FROM member WHERE email = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1), email).stream().findFirst().orElse(null);
    }

    public List<CartItem> findAll(String email) {
        return jdbcTemplate.query(ITEM_SELECT + " WHERE m.email = ? ORDER BY c.id", ITEM_MAPPER, email);
    }

    public Optional<CartItem> findOwned(long id, String email) {
        return jdbcTemplate.query(ITEM_SELECT + " WHERE c.id = ? AND m.email = ?",
                ITEM_MAPPER, id, email).stream().findFirst();
    }

    public Optional<CartItem> findByProduct(String productId, String email) {
        return jdbcTemplate.query(ITEM_SELECT + " WHERE c.product_id = ? AND m.email = ?",
                ITEM_MAPPER, productId, email).stream().findFirst();
    }

    public int insert(long memberId, String productId, int quantity) {
        return jdbcTemplate.update(
                "INSERT INTO shopping_cart(member_id, product_id, quantity) VALUES (?, ?, ?)",
                memberId, productId, quantity);
    }

    public int update(long id, long memberId, int quantity) {
        return jdbcTemplate.update(
                "UPDATE shopping_cart SET quantity = ? WHERE id = ? AND member_id = ?",
                quantity, id, memberId);
    }

    public int delete(long id, long memberId) {
        return jdbcTemplate.update("DELETE FROM shopping_cart WHERE id = ? AND member_id = ?", id, memberId);
    }

    public int clear(long memberId) {
        return jdbcTemplate.update("DELETE FROM shopping_cart WHERE member_id = ?", memberId);
    }
}
