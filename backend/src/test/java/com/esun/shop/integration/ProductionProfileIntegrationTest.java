package com.esun.shop.integration;

import com.esun.shop.model.Member;
import com.esun.shop.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.3 #18 B1: the real application, real MySQL, {@code prod} profile with a safe configuration. Proves it
 * starts, that health lives only on the separate management port (and is readable without a token there), and that
 * the main port neither serves health nor the API docs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        "MANAGEMENT_PORT=0", // resolved by application-prod.yml; proves the prod profile itself moves health off the main port
        "spring.data.redis.host=localhost",
        "jwt.secret=prod-profile-test-secret-0123456789abcdef-xyz",
        "cors.allowed-origins=https://shop.example.com",
        "payment.provider=none",
        "payment.callback-secret=prod-profile-test-callback-secret-0123"
})
class ProductionProfileIntegrationTest extends AbstractMySqlIntegrationTest {

    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JwtService jwtService;

    private ResponseEntity<String> get(int port, String path) {
        return rest.getForEntity("http://localhost:" + port + path, String.class);
    }

    @Test
    void healthIsServedOnTheManagementPort_withoutDetails() {
        assertThat(managementPort).isNotEqualTo(serverPort);

        ResponseEntity<String> liveness = get(managementPort, "/actuator/health/liveness");
        ResponseEntity<String> readiness = get(managementPort, "/actuator/health/readiness");

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveness.getBody()).contains("\"UP\"");
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).contains("\"UP\"").doesNotContain("jdbc").doesNotContain("components");
    }

    @Test
    void onlyHealthIsExposed_onTheManagementPort() {
        assertThat(get(managementPort, "/actuator/env").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(managementPort, "/actuator/beans").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * An unauthenticated request would be answered 401 by JwtAuthFilter before routing, which says nothing about
     * whether the endpoint exists. A valid token gets past the filter, so 404 here proves the main port has no
     * health handler at all (it would be 200 if the actuator were mounted there).
     */
    @Test
    void mainPortDoesNotServeHealth_evenToAnAuthenticatedCaller() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken("admin@example.com", Member.Role.ADMIN));

        ResponseEntity<String> response = rest.exchange("http://localhost:" + serverPort + "/actuator/health",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void apiDocsAreOffByDefaultInProd() {
        assertThat(get(serverPort, "/v3/api-docs").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(serverPort, "/swagger-ui.html").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
