package com.esun.shop.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-MySQL coverage of the seller product-management SQL and HTTP contract: owner-scoped search and
 * pagination, atomic all-or-nothing bulk operations, and image upload persistence / static serving.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ProductManagementIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
    private static final Path IMAGE_ROOT = createImageRoot();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void imageDirectory(DynamicPropertyRegistry registry) {
        registry.add("product.images.directory", IMAGE_ROOT::toString);
    }

    @Test
    void searchIsOwnerScopedPagedAndFiltersByStatus() throws Exception {
        String tag = tag();
        String seller = sellerToken("search-" + tag + "@example.com");
        String other = sellerToken("search-other-" + tag + "@example.com");
        create(seller, "S1-" + tag, "Green Tea " + tag, 10, 5);
        create(seller, "S2-" + tag, "Black TEA " + tag, 20, 5);
        create(seller, "S3-" + tag, "Cake " + tag, 30, 5);
        create(other, "O1-" + tag, "Foreign Tea " + tag, 40, 5);

        mvc.perform(get("/api/seller/products/search").header("Authorization", bearer(seller))
                        .param("keyword", "tea " + tag).param("size", "1").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.products.length()").value(1))
                .andExpect(jsonPath("$.data.products[0].productId").value("S1-" + tag));
        mvc.perform(get("/api/seller/products/search").header("Authorization", bearer(seller))
                        .param("keyword", "tea " + tag).param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.data.products[0].productId").value("S2-" + tag));
        mvc.perform(get("/api/seller/products/search").header("Authorization", bearer(seller))
                        .param("keyword", "O1-" + tag))
                .andExpect(jsonPath("$.data.total").value(0));
        // LIKE wildcards typed by the user must not widen the match to every product.
        mvc.perform(get("/api/seller/products/search").header("Authorization", bearer(seller))
                        .param("keyword", "%"))
                .andExpect(jsonPath("$.data.total").value(0));

        mvc.perform(delete("/api/admin/products/S3-" + tag).header("Authorization", bearer(seller))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/products/search").header("Authorization", bearer(seller))
                        .param("keyword", tag).param("status", "deleted"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.products[0].productId").value("S3-" + tag));
        mvc.perform(get("/api/admin/products/search").header("Authorization", bearer(seller))
                        .param("keyword", tag).param("status", "active"))
                .andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/admin/products/search").header("Authorization", bearer(seller)).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/products/search").header("Authorization", bearer(seller)).param("status", "bogus"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/products/search")).andExpect(status().isUnauthorized());
    }

    @Test
    void bulkOperationsAreAllOrNothingAndNeverTouchForeignProducts() throws Exception {
        String tag = tag();
        String seller = sellerToken("bulk-" + tag + "@example.com");
        String other = sellerToken("bulk-other-" + tag + "@example.com");
        create(seller, "B1-" + tag, "Bulk one", 10, 5);
        create(seller, "B2-" + tag, "Bulk two", 10, 5);
        create(other, "BF-" + tag, "Foreign", 10, 5);

        bulk(seller, List.of("B1-" + tag, "B2-" + tag), "RESTOCK", 7).andExpect(status().isOk());
        assertThat(quantity("B1-" + tag)).isEqualTo(12);
        assertThat(quantity("B2-" + tag)).isEqualTo(12);

        bulk(seller, List.of("B1-" + tag, "BF-" + tag), "RESTOCK", 100).andExpect(status().isForbidden());
        bulk(seller, List.of("B1-" + tag, "BF-" + tag), "DELETE", null).andExpect(status().isForbidden());
        bulk(seller, List.of("B1-" + tag, "MISSING-" + tag), "DELETE", null).andExpect(status().isForbidden());
        assertThat(quantity("B1-" + tag)).isEqualTo(12);
        assertThat(quantity("BF-" + tag)).isEqualTo(5);
        assertThat(deletedCount("B1-" + tag, "BF-" + tag)).isZero();

        bulk(seller, List.of("B1-" + tag, "B1-" + tag), "DELETE", null).andExpect(status().isBadRequest());
        bulk(seller, List.of("B1-" + tag), "RESTOCK", 0).andExpect(status().isBadRequest());
        bulk(seller, List.of("B1-" + tag), "RESTOCK", null).andExpect(status().isBadRequest());
        bulk(seller, List.of(), "DELETE", null).andExpect(status().isBadRequest());

        bulk(seller, List.of("B1-" + tag, "B2-" + tag), "DELETE", null).andExpect(status().isOk());
        assertThat(deletedCount("B1-" + tag, "B2-" + tag)).isEqualTo(2);
        assertThat(deletedCount("BF-" + tag)).isZero();
        mvc.perform(get("/api/products/available")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("B1-" + tag))));
        bulk(seller, List.of("B1-" + tag), "RESTOCK", 1).andExpect(status().isForbidden());
        assertThat(quantity("B1-" + tag)).isEqualTo(12);

        String buyer = buyerToken("bulk-buyer-" + tag + "@example.com");
        bulk(buyer, List.of("BF-" + tag), "DELETE", null).andExpect(status().isForbidden());
        assertThat(deletedCount("BF-" + tag)).isZero();
    }

    @Test
    void imageUploadPersistsOrderedMetadataServesFilesAndEnforcesOwnership() throws Exception {
        String tag = tag();
        String seller = sellerToken("image-" + tag + "@example.com");
        String other = sellerToken("image-other-" + tag + "@example.com");
        String buyer = buyerToken("image-buyer-" + tag + "@example.com");
        String productId = "I1-" + tag;
        create(seller, productId, "Image product", 10, 5);

        String response = mvc.perform(multipart("/api/seller/products/" + productId + "/images")
                        .file(new MockMultipartFile("images", "../../evil.png", "image/png", PNG))
                        .file(new MockMultipartFile("images", "second.png", "image/png", PNG))
                        .header("Authorization", bearer(seller)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode urls = mapper.readTree(response).path("data");
        assertThat(jdbc.queryForList("SELECT image_url FROM product_image WHERE product_id = ? ORDER BY display_order",
                String.class, productId)).containsExactly(urls.get(0).asText(), urls.get(1).asText());
        assertThat(jdbc.queryForList("SELECT display_order FROM product_image WHERE product_id = ? ORDER BY display_order",
                Integer.class, productId)).containsExactly(0, 1);
        assertThat(urls.get(0).asText()).startsWith("/uploads/products/").doesNotContain("evil", "..");
        assertThat(storedFiles()).hasSize(2);

        mvc.perform(get(urls.get(0).asText())).andExpect(status().isOk()).andExpect(content().bytes(PNG));
        mvc.perform(get("/uploads/products/../../application.yml")).andExpect(status().is4xxClientError());
        // 已登入者也不能藉資源 handler 走出上傳目錄、列出目錄，或讀到不存在的檔案。
        mvc.perform(get("/uploads/products/../../application.yml").header("Authorization", bearer(seller)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/uploads/products/").header("Authorization", bearer(seller))).andExpect(status().isNotFound());
        mvc.perform(get("/uploads/products/" + UUID.randomUUID() + ".png")).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/products/search").header("Authorization", bearer(seller)).param("keyword", productId))
                .andExpect(jsonPath("$.data.products[0].imageUrls.length()").value(2));

        mvc.perform(multipart("/api/seller/products/" + productId + "/images")
                        .file(new MockMultipartFile("images", "x.png", "image/png", PNG))
                        .header("Authorization", bearer(other))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/seller/products/" + productId + "/images")
                        .file(new MockMultipartFile("images", "x.png", "image/png", PNG))
                        .header("Authorization", bearer(buyer))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/seller/products/" + productId + "/images")
                        .file(new MockMultipartFile("images", "x.png", "image/png", PNG)))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/seller/products/" + productId + "/images")
                        .file(new MockMultipartFile("images", "x.png", "image/png", "<script/>".getBytes()))
                        .header("Authorization", bearer(seller))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/seller/products/MISSING-" + tag + "/images")
                        .file(new MockMultipartFile("images", "x.png", "image/png", PNG))
                        .header("Authorization", bearer(seller))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id = ?", Integer.class, productId))
                .isEqualTo(2);
        assertThat(storedFiles()).hasSize(2);
    }

    private org.springframework.test.web.servlet.ResultActions bulk(String token, List<String> ids, String action,
                                                                     Integer amount) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("productIds", ids);
        body.put("action", action);
        if (amount != null) body.put("amount", amount);
        return mvc.perform(post("/api/seller/products/bulk").header("Authorization", bearer(token))
                .contentType("application/json").content(mapper.writeValueAsString(body)));
    }

    private void create(String token, String id, String name, int price, int quantity) throws Exception {
        mvc.perform(post("/api/admin/products").header("Authorization", bearer(token)).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("productId", id, "productName", name,
                                "price", price, "quantity", quantity))))
                .andExpect(status().isOk());
    }

    private int quantity(String productId) {
        return jdbc.queryForObject("SELECT quantity FROM product WHERE product_id = ?", Integer.class, productId);
    }

    private int deletedCount(String... productIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(productIds.length, "?"));
        return jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE deleted_at IS NOT NULL AND product_id IN ("
                + placeholders + ")", Integer.class, (Object[]) productIds);
    }

    private String sellerToken(String email) throws Exception {
        register(email);
        jdbc.update("UPDATE member SET role = 'SELLER' WHERE email = ?", email);
        return login(email);
    }

    private String buyerToken(String email) throws Exception {
        register(email);
        return login(email);
    }

    private void register(String email) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk());
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<Path> storedFiles() throws IOException {
        try (Stream<Path> files = Files.list(IMAGE_ROOT)) {
            return files.toList();
        }
    }

    private static Path createImageRoot() {
        try {
            return Files.createTempDirectory("product-images-it");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
