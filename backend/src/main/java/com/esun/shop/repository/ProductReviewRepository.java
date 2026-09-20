package com.esun.shop.repository;

import com.esun.shop.model.ProductReview;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public class ProductReviewRepository {
    private static final String REVIEW_COLUMNS = """
            SELECT pr.id, pr.product_id, pr.member_id,
                   COALESCE(NULLIF(m.display_name, ''), '已購買買家') reviewer_name,
                   pr.rating, pr.content, pr.visibility, pr.created_at, pr.updated_at
            FROM product_review pr JOIN member m ON m.id = pr.member_id
            """;
    private static final RowMapper<ProductReview> MAPPER = (rs, rowNum) -> {
        ProductReview review = new ProductReview();
        review.setId(rs.getLong("id"));
        review.setProductId(rs.getString("product_id"));
        review.setMemberId(rs.getLong("member_id"));
        review.setReviewerName(rs.getString("reviewer_name"));
        review.setRating(rs.getInt("rating"));
        review.setContent(rs.getString("content"));
        review.setVisibility(ProductReview.Visibility.valueOf(rs.getString("visibility")));
        review.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        review.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return review;
    };

    private final JdbcTemplate jdbcTemplate;

    public ProductReviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasPurchased(Long memberId, String productId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM shop_order o
                JOIN order_detail d ON d.order_id = o.order_id
                JOIN member m ON m.email = o.member_id
                WHERE m.id = ? AND d.product_id = ?
                """, Integer.class, memberId, productId);
        return count != null && count > 0;
    }

    public void insert(String productId, Long memberId, int rating, String content) {
        jdbcTemplate.update("INSERT INTO product_review(product_id, member_id, rating, content) VALUES (?, ?, ?, ?)",
                productId, memberId, rating, content);
    }

    public ProductReview findById(long id) {
        return jdbcTemplate.query(REVIEW_COLUMNS + " WHERE pr.id = ?", MAPPER, id)
                .stream().findFirst().orElse(null);
    }

    public ProductReview findByMemberAndProduct(long memberId, String productId) {
        return jdbcTemplate.query(REVIEW_COLUMNS + " WHERE pr.member_id = ? AND pr.product_id = ?", MAPPER,
                memberId, productId).stream().findFirst().orElse(null);
    }

    public List<ProductReview> findVisible(String productId, String orderBy, int size, int offset) {
        return jdbcTemplate.query(REVIEW_COLUMNS
                + " WHERE pr.product_id = ? AND pr.visibility = 'VISIBLE' ORDER BY " + orderBy
                + " LIMIT ? OFFSET ?", MAPPER, productId, size, offset);
    }

    public long countVisible(String productId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_review WHERE product_id = ? AND visibility = 'VISIBLE'",
                Long.class, productId);
        return count == null ? 0 : count;
    }

    public BigDecimal averageVisible(String productId) {
        BigDecimal average = jdbcTemplate.queryForObject(
                "SELECT ROUND(AVG(rating), 2) FROM product_review WHERE product_id = ? AND visibility = 'VISIBLE'",
                BigDecimal.class, productId);
        return average == null ? BigDecimal.ZERO.setScale(2) : average;
    }

    public int updateOwned(long id, long memberId, int rating, String content) {
        return jdbcTemplate.update(
                "UPDATE product_review SET rating = ?, content = ? WHERE id = ? AND member_id = ?",
                rating, content, id, memberId);
    }

    public int deleteOwned(long id, long memberId) {
        return jdbcTemplate.update("DELETE FROM product_review WHERE id = ? AND member_id = ?", id, memberId);
    }

    public List<ProductReview> findForSeller(String creatorId, String productId, int size, int offset) {
        String filter = productId == null ? "" : " AND pr.product_id = ?";
        Object[] args = productId == null
                ? new Object[] { creatorId, size, offset }
                : new Object[] { creatorId, productId, size, offset };
        return jdbcTemplate.query(REVIEW_COLUMNS
                + " JOIN product p ON p.product_id = pr.product_id"
                + " WHERE p.creator_id = ?" + filter
                + " ORDER BY pr.created_at DESC, pr.id DESC LIMIT ? OFFSET ?", MAPPER, args);
    }

    public List<ProductReview> findForAdmin(String productId, int size, int offset) {
        String filter = productId == null ? "" : " WHERE pr.product_id = ?";
        Object[] args = productId == null
                ? new Object[] { size, offset }
                : new Object[] { productId, size, offset };
        return jdbcTemplate.query(REVIEW_COLUMNS + filter
                + " ORDER BY pr.created_at DESC, pr.id DESC LIMIT ? OFFSET ?", MAPPER, args);
    }

    public int setVisibility(long id, ProductReview.Visibility visibility) {
        return jdbcTemplate.update("UPDATE product_review SET visibility = ? WHERE id = ?",
                visibility.name(), id);
    }
}
