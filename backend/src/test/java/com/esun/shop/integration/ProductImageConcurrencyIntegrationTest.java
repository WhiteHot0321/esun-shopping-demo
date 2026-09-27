package com.esun.shop.integration;

import com.esun.shop.model.Member;
import com.esun.shop.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ProductImageConcurrencyIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final Path ROOT = imageRoot();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4};
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;

    @DynamicPropertySource
    static void images(DynamicPropertyRegistry registry) {
        registry.add("product.images.directory", ROOT::toString);
    }

    @Test
    void concurrentUploadsCannotExceedTenImages() throws Exception {
        concurrentUploads(9, 400, 1);
    }

    @Test
    void concurrentUploadsUseDistinctDisplayOrders() throws Exception {
        concurrentUploads(8, 200, 2);
    }

    private void concurrentUploads(int initial, int secondStatus, int added) throws Exception {
        String id = seed(initial);
        String token = jwt.generateToken("image-owner@example.com", Member.Role.SELLER);
        long before = fileCount();
        var executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = rootConnection(); Connection observer = rootConnection()) {
            long connectionId = lock(blocker, id);
            var first = executor.submit(() -> upload(id, token));
            var second = executor.submit(() -> upload(id, token));
            try {
                awaitWaiters(observer, connectionId, 2);
            } finally {
                blocker.commit();
            }
            assertThat(java.util.List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, secondStatus);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id=?", Integer.class, id))
                    .isEqualTo(initial + added);
            assertThat(jdbc.queryForList("SELECT display_order FROM product_image WHERE product_id=? ORDER BY display_order", Integer.class, id))
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0, initial + added).boxed().toList());
            assertThat(fileCount() - before).isEqualTo(added);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_id=? AND action='PRODUCT_IMAGE_UPLOAD'", Integer.class, id)).isEqualTo(added);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void waitingUploadRechecksDeletedStateAndOwnership(boolean deleted) throws Exception {
        String id = seed(0);
        String token = jwt.generateToken("image-owner@example.com", Member.Role.SELLER);
        long before = fileCount();
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = rootConnection(); Connection observer = rootConnection()) {
            long connectionId = lock(blocker, id);
            var upload = executor.submit(() -> upload(id, token));
            try {
                awaitWaiters(observer, connectionId, 1);
                try (var statement = blocker.prepareStatement(deleted
                        ? "UPDATE product SET deleted_at=NOW() WHERE product_id=?"
                        : "UPDATE product SET creator_id='another-owner' WHERE product_id=?")) {
                    statement.setString(1, id);
                    statement.executeUpdate();
                }
            } finally {
                blocker.commit();
            }
            assertThat(upload.get(30, TimeUnit.SECONDS)).isEqualTo(deleted ? 404 : 403);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id=?", Integer.class, id)).isZero();
            assertThat(fileCount()).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_id=?", Integer.class, id)).isZero();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String seed(int count) {
        String id = "IC" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.update("INSERT INTO product(product_id, product_name, price, quantity, creator_id) VALUES (?, 'Image concurrency', 100, 1, 'image-owner@example.com')", id);
        for (int i = 0; i < count; i++) {
            jdbc.update("INSERT INTO product_image(product_id, image_url, display_order) VALUES (?, ?, ?)", id, "/seed/" + i, i);
        }
        return id;
    }

    private int upload(String id, String token) throws Exception {
        return mvc.perform(multipart("/api/seller/products/{id}/images", id)
                .file(new MockMultipartFile("images", "test.png", "image/png", PNG))
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    private static Connection rootConnection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }

    private static long lock(Connection connection, String id) throws Exception {
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT product_id FROM product WHERE product_id=? FOR UPDATE")) {
            statement.setString(1, id);
            try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
        }
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            result.next();
            return result.getLong(1);
        }
    }

    // Observe real InnoDB contention, not sleeps or mocked repository synchronization.
    private static void awaitWaiters(Connection observer, long connectionId, int expected) {
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            try (var statement = observer.prepareStatement("""
                    SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID)
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.threads t ON t.THREAD_ID=w.BLOCKING_THREAD_ID
                    WHERE t.PROCESSLIST_ID=?
                    """)) {
                statement.setLong(1, connectionId);
                try (var result = statement.executeQuery()) { result.next(); return result.getInt(1) >= expected; }
            }
        });
    }

    private static long fileCount() throws Exception {
        try (var files = Files.list(ROOT)) { return files.count(); }
    }

    private static Path imageRoot() {
        try { return Files.createTempDirectory("image-concurrency-it"); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
