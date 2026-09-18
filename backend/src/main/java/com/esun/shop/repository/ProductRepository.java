package com.esun.shop.repository;

import com.esun.shop.llm.IndexableDoc;
import com.esun.shop.model.Product;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.Collections;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ProductRepository {
    private static final RowMapper<Product> PRODUCT_ROW_MAPPER = (rs, rowNum) -> {
        Product p = new Product();
        p.setProductId(rs.getString("product_id"));
        p.setProductName(rs.getString("product_name"));
        p.setPrice(rs.getBigDecimal("price"));
        p.setQuantity(rs.getInt("quantity"));
        p.setCreatorId(rs.getString("creator_id"));
        var deletedAt = rs.getTimestamp("deleted_at");
        p.setDeletedAt(deletedAt == null ? null : deletedAt.toLocalDateTime());
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
                .returningResultSet("products", PRODUCT_ROW_MAPPER);
        this.decreaseStockCall = new SimpleJdbcCall(dataSource)
                .withProcedureName("sp_decrease_stock");
    }

    public void addProduct(Product product) {
        jdbcTemplate.update("INSERT INTO product(product_id, product_name, price, quantity, creator_id) VALUES (?, ?, ?, ?, ?)",
                product.getProductId(), product.getProductName(), product.getPrice(), product.getQuantity(), product.getCreatorId());
    }

    @SuppressWarnings("unchecked")
    public List<Product> getAvailableProducts() {
        return jdbcTemplate.query(selectProductColumns() + " WHERE quantity > 0 AND deleted_at IS NULL ORDER BY product_id",
                PRODUCT_ROW_MAPPER);
    }

    public Product findById(String productId) {
        String sql = selectProductColumns() + " WHERE product_id = ? AND deleted_at IS NULL";
        List<Product> list = jdbcTemplate.query(sql, PRODUCT_ROW_MAPPER, productId);
        return list.isEmpty() ? null : list.get(0);
    }

    public Product findIncludingDeletedById(String productId) {
        List<Product> list = jdbcTemplate.query(selectProductColumns() + " WHERE product_id = ?", PRODUCT_ROW_MAPPER, productId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Owner administration needs both active and soft-deleted rows; public paths remain filtered. */
    public List<Product> findByCreator(String creatorId) {
        return jdbcTemplate.query(selectProductColumns() + " WHERE creator_id = ? ORDER BY product_id",
                PRODUCT_ROW_MAPPER, creatorId);
    }

    public List<Product> findAllStock() {
        return jdbcTemplate.query(selectProductColumns() + " WHERE deleted_at IS NULL", PRODUCT_ROW_MAPPER);
    }

    /**
     * 一次撈回多筆商品，供建立訂單時以 Map 查詢，避免每個品項各打一次 SELECT。
     */
    public List<Product> findByIds(Collection<String> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        String sql = selectProductColumns() + " WHERE product_id IN (" + placeholders + ") AND deleted_at IS NULL";
        return jdbcTemplate.query(sql, PRODUCT_ROW_MAPPER, productIds.toArray());
    }

    public List<IndexableDoc> findAllForIndexing() {
        String sql = "SELECT product_id, product_name, updated_at FROM product WHERE deleted_at IS NULL";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new IndexableDoc(
                "product",
                rs.getString("product_id"),
                rs.getString("product_name"),
                rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    public void decreaseStock(String productId, Integer quantity) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_product_id", productId);
        params.put("p_buy_quantity", quantity);
        decreaseStockCall.execute(params);
    }

    public int updateOwnedProduct(String productId, String creatorId, String productName, java.math.BigDecimal price) {
        return jdbcTemplate.update("UPDATE product SET product_name = ?, price = ? "
                        + "WHERE product_id = ? AND creator_id = ? AND deleted_at IS NULL",
                productName, price, productId, creatorId);
    }

    public int softDeleteOwnedProduct(String productId, String creatorId) {
        return jdbcTemplate.update("UPDATE product SET deleted_at = CURRENT_TIMESTAMP "
                        + "WHERE product_id = ? AND creator_id = ? AND deleted_at IS NULL", productId, creatorId);
    }

    /** A single conditional SQL update prevents concurrent restocks from losing increments. */
    public int restockOwnedProduct(String productId, String creatorId, int amount) {
        return jdbcTemplate.update("UPDATE product SET quantity = quantity + ? "
                        + "WHERE product_id = ? AND creator_id = ? AND deleted_at IS NULL",
                amount, productId, creatorId);
    }

    private static String selectProductColumns() {
        return "SELECT product_id, product_name, price, quantity, creator_id, deleted_at FROM product";
    }
}
