package com.esun.shop.repository;

import com.esun.shop.model.Product;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.Collections;
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
        Map<String, Object> params = new HashMap<>();
        params.put("p_product_id", product.getProductId());
        params.put("p_product_name", product.getProductName());
        params.put("p_price", product.getPrice());
        params.put("p_quantity", product.getQuantity());
        addProductCall.execute(params);
    }

    @SuppressWarnings("unchecked")
    public List<Product> getAvailableProducts() {
        Map<String, Object> result = getAvailableProductsCall.execute(new HashMap<>());
        return (List<Product>) result.get("products");
    }

    public Product findById(String productId) {
        String sql = "SELECT product_id, product_name, price, quantity FROM product WHERE product_id = ?";
        List<Product> list = jdbcTemplate.query(sql, PRODUCT_ROW_MAPPER, productId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 一次撈回多筆商品，供建立訂單時以 Map 查詢，避免每個品項各打一次 SELECT。
     */
    public List<Product> findByIds(Collection<String> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        String sql = "SELECT product_id, product_name, price, quantity FROM product WHERE product_id IN (" + placeholders + ")";
        return jdbcTemplate.query(sql, PRODUCT_ROW_MAPPER, productIds.toArray());
    }

    public void decreaseStock(String productId, Integer quantity) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_product_id", productId);
        params.put("p_buy_quantity", quantity);
        decreaseStockCall.execute(params);
    }
}
