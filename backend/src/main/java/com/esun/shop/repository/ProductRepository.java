package com.esun.shop.repository;

import com.esun.shop.llm.IndexableDoc;
import com.esun.shop.model.Product;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

@Repository
public class ProductRepository {
    private static final RowMapper<Product> AVAILABLE_PRODUCT_ROW_MAPPER = (rs, rowNum) -> {
        Product p = new Product();
        p.setProductId(rs.getString("product_id"));
        p.setProductName(rs.getString("product_name"));
        p.setPrice(rs.getBigDecimal("price"));
        p.setQuantity(rs.getInt("quantity"));
        return p;
    };
    private static final RowMapper<Product> PRODUCT_ROW_MAPPER = (rs, rowNum) -> {
        Product p = new Product();
        p.setProductId(rs.getString("product_id"));
        p.setProductName(rs.getString("product_name"));
        p.setPrice(rs.getBigDecimal("price"));
        p.setQuantity(rs.getInt("quantity"));
        p.setCreatorId(rs.getString("creator_id"));
        return p;
    };

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcCall addProductCall;
    private final SimpleJdbcCall getAvailableProductsCall;
    private final SimpleJdbcCall decreaseStockCall;

    public ProductRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.addProductCall = new SimpleJdbcCall(dataSource)
                .withProcedureName("sp_add_product");
        this.getAvailableProductsCall = new SimpleJdbcCall(dataSource)
                .withProcedureName("sp_get_available_products")
                .returningResultSet("products", AVAILABLE_PRODUCT_ROW_MAPPER);
        this.decreaseStockCall = new SimpleJdbcCall(dataSource)
                .withProcedureName("sp_decrease_stock");
    }

    public void addProduct(Product product) {
        jdbcTemplate.update("INSERT INTO product(product_id, product_name, price, quantity, creator_id) VALUES (?, ?, ?, ?, ?)",
                product.getProductId(), product.getProductName(), product.getPrice(), product.getQuantity(),
                product.getCreatorId());
    }

    @SuppressWarnings("unchecked")
    public List<Product> getAvailableProducts() {
        Map<String, Object> result = getAvailableProductsCall.execute(new HashMap<>());
        List<Product> products = (List<Product>) result.get("products");
        Map<String, RatingSummary> summaries = jdbcTemplate.query(
                "SELECT product_id, ROUND(AVG(rating), 2) average_rating, COUNT(*) review_count "
                        + "FROM product_review WHERE visibility = 'VISIBLE' GROUP BY product_id",
                rs -> {
                    Map<String, RatingSummary> values = new HashMap<>();
                    while (rs.next()) values.put(rs.getString("product_id"),
                            new RatingSummary(rs.getBigDecimal("average_rating"), rs.getLong("review_count")));
                    return values;
                });
        products.forEach(product -> {
            RatingSummary summary = summaries.get(product.getProductId());
            if (summary != null) {
                product.setAverageRating(summary.average());
                product.setReviewCount(summary.count());
            }
        });
        return products;
    }

    public Product findById(String productId) {
        String sql = "SELECT product_id, product_name, price, quantity, creator_id FROM product WHERE product_id = ?";
        List<Product> list = jdbcTemplate.query(sql, PRODUCT_ROW_MAPPER, productId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Product> findAllStock() {
        return jdbcTemplate.query("SELECT product_id, product_name, price, quantity, creator_id FROM product", PRODUCT_ROW_MAPPER);
    }

    /**
     * 一次撈回多筆商品，供建立訂單時以 Map 查詢，避免每個品項各打一次 SELECT。
     */
    public List<Product> findByIds(Collection<String> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        String sql = "SELECT product_id, product_name, price, quantity, creator_id FROM product WHERE product_id IN (" + placeholders + ")";
        return jdbcTemplate.query(sql, PRODUCT_ROW_MAPPER, productIds.toArray());
    }

    public List<IndexableDoc> findAllForIndexing() {
        String sql = "SELECT product_id, product_name, price, quantity, updated_at FROM product";
        return jdbcTemplate.query(sql, (rs, rowNum) -> toIndexableDoc(rs));
    }

    /**
     * 撈單一商品供索引用，供新增/異動商品後即時 upsert 該筆 embedding，
     * 不必等下次應用程式啟動才重新索引。
     */
    public IndexableDoc findByIdForIndexing(String productId) {
        String sql = "SELECT product_id, product_name, price, quantity, updated_at FROM product WHERE product_id = ?";
        List<IndexableDoc> list = jdbcTemplate.query(sql, (rs, rowNum) -> toIndexableDoc(rs), productId);
        return list.isEmpty() ? null : list.get(0);
    }

    private IndexableDoc toIndexableDoc(ResultSet rs) throws SQLException {
        String content = "商品名稱：%s；價格：NT$%s；庫存：%d 件".formatted(
                rs.getString("product_name"),
                rs.getBigDecimal("price").toPlainString(),
                rs.getInt("quantity"));
        return new IndexableDoc(
                "product",
                rs.getString("product_id"),
                content,
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    public void decreaseStock(String productId, Integer quantity) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_product_id", productId);
        params.put("p_buy_quantity", quantity);
        decreaseStockCall.execute(params);
    }

    public boolean isOwnedBy(String productId, String creatorId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product WHERE product_id = ? AND creator_id = ?",
                Integer.class, productId, creatorId);
        return count != null && count == 1;
    }

    private record RatingSummary(BigDecimal average, long count) { }
}
