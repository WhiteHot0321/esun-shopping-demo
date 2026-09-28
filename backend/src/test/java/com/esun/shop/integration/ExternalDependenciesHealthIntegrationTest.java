package com.esun.shop.integration;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.model.PayStatus;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.StockCacheService;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "management.server.port=0",
        "stock.redis.enabled=true",
        "stock.redis.audit-interval-ms=3600000",
        "spring.data.redis.timeout=200ms",
        "spring.data.redis.connect-timeout=200ms",
        "llm.indexing.enabled=false",
        "management.health.dependencies.timeout-ms=200"
})
class ExternalDependenciesHealthIntegrationTest extends AbstractMySqlIntegrationTest {
    enum OllamaMode { PRESENT, MISSING, REFUSED, TIMEOUT }

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    static final AtomicReference<OllamaMode> OLLAMA_MODE = new AtomicReference<>(OllamaMode.PRESENT);
    static final HttpServer OLLAMA;

    static {
        REDIS.start();
        try {
            OLLAMA = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            OLLAMA.createContext("/api/tags", ExternalDependenciesHealthIntegrationTest::tags);
            OLLAMA.start();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void dependencyProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("llm.ollama.base-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired StringRedisTemplate redis;
    @Autowired StockCacheService cache;
    @Autowired OrderService orders;
    @LocalManagementPort int managementPort;

    @Test
    void optionalDependencyFailuresStayOutOfReadiness_andRedisRecoveryDoesNotClearLatch() throws Exception {
        OLLAMA_MODE.set(OllamaMode.PRESENT);
        ResponseEntity<String> healthy = dependencies();
        assertThat(healthy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(healthy.getBody())
                .contains("REACHABLE_MODELS_PRESENT", "TAG_INVENTORY_ONLY", "\"mode\":\"ENABLED\"")
                .doesNotContain("llama3.1", "nomic-embed-text");
        assertReady();

        OLLAMA_MODE.set(OllamaMode.MISSING);
        assertThat(dependencies().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(dependencies().getBody()).contains("REACHABLE_MISSING_MODELS", "\"missingModelCount\":1");
        assertReady();

        OLLAMA_MODE.set(OllamaMode.TIMEOUT);
        Instant started = Instant.now();
        ResponseEntity<String> timeout = dependencies();
        assertThat(timeout.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(timeout.getBody()).contains("TIMEOUT");
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(2));
        assertReady();

        OLLAMA_MODE.set(OllamaMode.REFUSED);
        ResponseEntity<String> refused = dependencies();
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getBody()).contains("UNAVAILABLE").doesNotContain("llama3.1", "nomic-embed-text");
        assertReady();
        OLLAMA_MODE.set(OllamaMode.PRESENT);

        String product = "MON02-" + UUID.randomUUID().toString().substring(0, 8);
        db.update("INSERT INTO product(product_id,product_name,price,quantity) VALUES (?,?,10,2)", product, product);
        cache.preload();

        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            ResponseEntity<String> unavailable = dependencies();
            assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(unavailable.getBody()).contains("CONNECTION_FAILED", "UNAVAILABLE");
            assertReady();

            String orderNo = orders.createOrder(request(product));
            assertThat(orderNo).isNotBlank();
            assertThat(db.queryForObject("SELECT quantity FROM product WHERE product_id=?", Integer.class, product))
                    .isEqualTo(1);
            assertThat(cache.dependencySnapshot().mode())
                    .isEqualTo(StockCacheService.DependencyMode.LATCHED_DB_ONLY);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        try (var connection = redis.getConnectionFactory().getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
        ResponseEntity<String> recoveredConnectivity = dependencies();
        assertThat(recoveredConnectivity.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(recoveredConnectivity.getBody()).contains("LATCHED_DB_ONLY", "AVAILABLE");
        assertReady();
    }

    private ResponseEntity<String> dependencies() {
        return http.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/health/dependencies", String.class);
    }

    private void assertReady() {
        assertThat(http.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/health/readiness", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private CreateOrderRequest request(String product) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(product);
        item.setQuantity(1);
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setMemberId("LIVE-OUTAGE");
        request.setPayStatus(PayStatus.PENDING);
        request.setItems(List.of(item));
        return request;
    }

    private static void tags(HttpExchange exchange) throws IOException {
        OllamaMode mode = OLLAMA_MODE.get();
        if (mode == OllamaMode.REFUSED) {
            byte[] error = "{\"error\":\"controlled refusal\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
            return;
        }
        if (mode == OllamaMode.TIMEOUT) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        String body = mode == OllamaMode.MISSING
                ? "{\"models\":[{\"name\":\"llama3.1:latest\"}]}"
                : "{\"models\":[{\"name\":\"llama3.1:latest\"},{\"model\":\"nomic-embed-text:latest\"}]}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // Expected when the bounded client timeout closes the connection first.
        } finally {
            exchange.close();
        }
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "management.server.port=0",
        "stock.redis.enabled=false",
        "llm.indexing.enabled=false",
        "management.health.dependencies.timeout-ms=200"
})
class RedisDisabledDependenciesHealthIntegrationTest extends AbstractMySqlIntegrationTest {
    @DynamicPropertySource
    static void ollamaProperty(DynamicPropertyRegistry registry) {
        registry.add("llm.ollama.base-url", () -> "http://127.0.0.1:"
                + ExternalDependenciesHealthIntegrationTest.OLLAMA.getAddress().getPort());
    }

    @Autowired TestRestTemplate http;
    @LocalManagementPort int managementPort;

    @Test
    void intentionallyDisabledRedisIsReportedWithoutAConnectionAttemptOrFailure() {
        ExternalDependenciesHealthIntegrationTest.OLLAMA_MODE
                .set(ExternalDependenciesHealthIntegrationTest.OllamaMode.PRESENT);
        ResponseEntity<String> response = http.getForEntity(
                "http://127.0.0.1:" + managementPort + "/actuator/health/dependencies", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"mode\":\"DISABLED\"", "NOT_PROBED");
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "management.server.port=0",
        "stock.redis.enabled=false",
        "llm.provider=claude",
        "llm.indexing.enabled=false",
        "spring.datasource.hikari.connection-timeout=500",
        "spring.datasource.hikari.validation-timeout=250",
        "spring.datasource.hikari.data-source-properties.connectTimeout=500",
        "spring.datasource.hikari.data-source-properties.socketTimeout=500"
})
class DatabaseReadinessHealthIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired TestRestTemplate http;
    @LocalManagementPort int managementPort;

    @Test
    void databaseOutageFailsReadinessButNotLiveness() {
        assertThat(get("/actuator/health/readiness").getStatusCode()).isEqualTo(HttpStatus.OK);
        MYSQL.getDockerClient().pauseContainerCmd(MYSQL.getContainerId()).exec();
        try {
            Instant started = Instant.now();
            assertThat(get("/actuator/health/readiness").getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(3));
            assertThat(get("/actuator/health/liveness").getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            MYSQL.getDockerClient().unpauseContainerCmd(MYSQL.getContainerId()).exec();
        }
        assertReadinessRecovers();
    }

    private ResponseEntity<String> get(String path) {
        return http.getForEntity("http://127.0.0.1:" + managementPort + path, String.class);
    }

    private void assertReadinessRecovers() {
        ResponseEntity<String> response = null;
        for (int attempt = 0; attempt < 15; attempt++) {
            response = get("/actuator/health/readiness");
            if (response.getStatusCode() == HttpStatus.OK) return;
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for MySQL health recovery", ex);
            }
        }
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
