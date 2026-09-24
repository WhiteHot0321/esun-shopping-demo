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
import java.util.ArrayList;
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
        attachImages(products);
        return products;
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

    /** 取得列鎖後讀取（僅供交易內使用），讓稽核的 before 快照不會被並發寫入蓋掉。 */
    public Product lockIncludingDeletedById(String productId) {
        List<Product> list = jdbcTemplate.query(selectProductColumns() + " WHERE product_id = ? FOR UPDATE",
                PRODUCT_ROW_MAPPER, productId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Product> findIncludingDeletedByIds(Collection<String> productIds) {
        if (productIds.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        return jdbcTemplate.query(selectProductColumns() + " WHERE product_id IN (" + placeholders
                + ") ORDER BY product_id", PRODUCT_ROW_MAPPER, productIds.toArray());
    }

    public List<Product> findByCreator(String creatorId) {
        List<Product> products = jdbcTemplate.query(selectProductColumns() + " WHERE creator_id = ? ORDER BY product_id",
                PRODUCT_ROW_MAPPER, creatorId);
        attachImages(products);
        return products;
    }

    public List<Product> searchByCreator(String creatorId, String keyword, String status, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = managementWhere(creatorId, keyword, status, args);
        args.add(limit);
        args.add(offset);
        List<Product> products = jdbcTemplate.query(selectProductColumns() + where
                + " ORDER BY product_id LIMIT ? OFFSET ?", PRODUCT_ROW_MAPPER, args.toArray());
        attachImages(products);
        return products;
    }

    public long countByCreator(String creatorId, String keyword, String status) {
        List<Object> args = new ArrayList<>();
        String where = managementWhere(creatorId, keyword, status, args);
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product" + where, Long.class, args.toArray());
        return count == null ? 0 : count;
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
        String sql = "SELECT product_id, product_name, price, quantity, updated_at FROM product WHERE deleted_at IS NULL";
        return jdbcTemplate.query(sql, (rs, rowNum) -> toIndexableDoc(rs));
    }

    /**
     * 撈單一商品供索引用，供新增/異動商品後即時 upsert 該筆 embedding，
     * 不必等下次應用程式啟動才重新索引。
     */
    public IndexableDoc findByIdForIndexing(String productId) {
        String sql = "SELECT product_id, product_name, price, quantity, updated_at FROM product WHERE product_id = ? AND deleted_at IS NULL";
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

    public int updateOwnedProduct(String productId, String creatorId, String productName, BigDecimal price) {
        return jdbcTemplate.update("UPDATE product SET product_name = ?, price = ? "
                        + "WHERE product_id = ? AND creator_id = ? AND deleted_at IS NULL",
                productName, price, productId, creatorId);
    }

    public int softDeleteOwnedProduct(String productId, String creatorId) {
        return jdbcTemplate.update("UPDATE product SET deleted_at = CURRENT_TIMESTAMP "
                        + "WHERE product_id = ? AND creator_id = ? AND deleted_at IS NULL",
                productId, creatorId);
    }

    public int restockOwnedProduct(String productId, String creatorId, int amount) {
        return jdbcTemplate.update("UPDATE product SET quantity = quantity + ? "
                        + "WHERE product_id = ? AND creator_id = ? AND deleted_at IS NULL",
                amount, productId, creatorId);
    }

    public int countActiveOwned(Collection<String> productIds, String creatorId) {
        if (productIds.isEmpty()) return 0;
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        List<Object> args = new ArrayList<>(productIds);
        args.add(creatorId);
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product WHERE product_id IN ("
                + placeholders + ") AND creator_id = ? AND deleted_at IS NULL", Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    public int bulkSoftDeleteOwned(Collection<String> productIds, String creatorId) {
        return bulkUpdate("UPDATE product SET deleted_at = CURRENT_TIMESTAMP WHERE product_id IN (%s) "
                + "AND creator_id = ? AND deleted_at IS NULL", productIds, creatorId, null);
    }

    public int bulkRestockOwned(Collection<String> productIds, String creatorId, int amount) {
        return bulkUpdate("UPDATE product SET quantity = quantity + ? WHERE product_id IN (%s) "
                + "AND creator_id = ? AND deleted_at IS NULL", productIds, creatorId, amount);
    }

    public int countProductImages(String productId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_image WHERE product_id = ?", Integer.class, productId);
        return count == null ? 0 : count;
    }

    public void addProductImage(String productId, String imageUrl) {
        Integer order = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(display_order), -1) + 1 FROM product_image WHERE product_id = ?",
                Integer.class, productId);
        jdbcTemplate.update("INSERT INTO product_image(product_id, image_url, display_order) VALUES (?, ?, ?)",
                productId, imageUrl, order == null ? 0 : order);
    }

    private int bulkUpdate(String template, Collection<String> productIds, String creatorId, Integer amount) {
        if (productIds.isEmpty()) return 0;
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        if (amount != null) args.add(amount);
        args.addAll(productIds);
        args.add(creatorId);
        return jdbcTemplate.update(template.formatted(placeholders), args.toArray());
    }

    private static String managementWhere(String creatorId, String keyword, String status, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE creator_id = ?");
        args.add(creatorId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (LOWER(product_id) LIKE ? ESCAPE '\\\\' OR LOWER(product_name) LIKE ? ESCAPE '\\\\')");
            // 使用者輸入的 % / _ / \ 視為字面字元，避免關鍵字變成萬用字元而匹配全部商品。
            String escaped = keyword.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            String term = "%" + escaped + "%";
            args.add(term);
            args.add(term);
        }
        if ("active".equals(status)) where.append(" AND deleted_at IS NULL");
        else if ("deleted".equals(status)) where.append(" AND deleted_at IS NOT NULL");
        return where.toString();
    }

    private void attachImages(List<Product> products) {
        if (products.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(products.size(), "?"));
        Map<String, Product> byId = new HashMap<>();
        products.forEach(product -> byId.put(product.getProductId(), product));
        jdbcTemplate.query("SELECT product_id, image_url FROM product_image WHERE product_id IN (" + placeholders
                        + ") ORDER BY product_id, display_order, image_id", rs -> {
                    Product product = byId.get(rs.getString("product_id"));
                    if (product != null) product.getImageUrls().add(rs.getString("image_url"));
                }, products.stream().map(Product::getProductId).toArray());
    }

    private static String selectProductColumns() {
        return "SELECT product_id, product_name, price, quantity, creator_id, deleted_at FROM product";
    }

    private record RatingSummary(BigDecimal average, long count) { }
}
