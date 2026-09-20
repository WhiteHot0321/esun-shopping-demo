package com.esun.shop.integration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Shared real-MySQL fixture for the Testcontainers integration tests.
 *
 * Loads the actual backend/DB/01_schema.sql -> 02_data.sql -> 03_stored_procedures.sql ->
 * 04_faq.sql -> 04_member.sql -> 05_password_reset_token.sql -> 06_member_profile.sql ->
 * 07_shipping_address.sql -> 08_shopping_cart.sql scripts (the same files
 * docker-compose.yml mounts into /docker-entrypoint-initdb.d)
 * via withCopyFileToContainer, relying on the official mysql image running everything
 * under /docker-entrypoint-initdb.d in alphabetical (01/02/03) order - no hand-rolled
 * reduced schema, so this is exercising the real DDL/stored procedures.
 *
 * Uses Testcontainers' "singleton container" pattern: the container is started once in a
 * static initializer and never explicitly stopped, instead of the usual @Container-managed
 * per-class lifecycle. Surefire runs every test class in one JVM, and this field is
 * *inherited*, not redeclared, by each subclass - with @Container that means every subclass
 * shares the exact same field, so JUnit stops it in the first subclass's afterAll and every
 * later subclass fails to connect to an already-dead container. Ryuk (Testcontainers' own
 * resource reaper) tears the container down when the JVM exits, so skipping stop() here is
 * safe and is the documented way to share one container across multiple test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractMySqlIntegrationTest {

    private static final String[] LEGACY_ORDER_MEMBERS = {
            "MEMBER-A", "MEMBER-B", "MEMBER-X", "MEMBER-Y", "MEMBER-ROLLBACK",
            "IDEM-MEMBER", "IDEM-DISTINCT", "IDEM-ROLLBACK", "IDEM-OWNER", "IDEM-OTHER",
            "IT-MEMBER", "QC-MEMBER-1", "QC-MEMBER-2", "QC-MEMBER-3", "DEADLOCK-TEST",
            "LIVE-OUTAGE", "REDIS-TEST", "OUTAGE-TEST"
    };

    @Autowired
    private JdbcTemplate addressFixtureJdbc;

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("esun_shop")
            // Without this, the mysql image's default character_set_client (latin1) mangles the
            // UTF-8 Chinese text in 02_data.sql/04_faq.sql while the *.sql files run during
            // container init, double-encoding every product/FAQ string - same fix as
            // docker-compose.yml's mysql service.
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci",
                    "--character-set-client-handshake=FALSE")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("DB/01_schema.sql"), "/docker-entrypoint-initdb.d/01_schema.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("DB/02_data.sql"), "/docker-entrypoint-initdb.d/02_data.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("DB/03_stored_procedures.sql"), "/docker-entrypoint-initdb.d/03_stored_procedures.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("DB/04_faq.sql"), "/docker-entrypoint-initdb.d/04_faq.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("DB/04_member.sql"), "/docker-entrypoint-initdb.d/04_member.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("DB/05_password_reset_token.sql"),
                    "/docker-entrypoint-initdb.d/05_password_reset_token.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("DB/06_member_profile.sql"),
                    "/docker-entrypoint-initdb.d/06_member_profile.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("DB/07_shipping_address.sql"),
                    "/docker-entrypoint-initdb.d/07_shipping_address.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("DB/08_shopping_cart.sql"),
                    "/docker-entrypoint-initdb.d/08_shopping_cart.sql");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /**
     * Existing order integration tests predate the address book and use synthetic member
     * identifiers directly at the service boundary. Give those test members a real default
     * address so the tests continue to exercise their original order/concurrency concerns
     * while production still rejects checkout when an authenticated member has no address.
     */
    @BeforeEach
    void seedLegacyOrderAddressFixtures() {
        for (String member : LEGACY_ORDER_MEMBERS) {
            seedDefaultAddress(member);
        }
        for (int i = 0; i < 20; i++) {
            seedDefaultAddress("HCD-MEMBER-" + i);
            seedDefaultAddress("HCS-MEMBER-" + i);
        }
    }

    private void seedDefaultAddress(String email) {
        addressFixtureJdbc.update(
                "INSERT IGNORE INTO member (email, password_hash) VALUES (?, 'integration-test')", email);
        addressFixtureJdbc.update("""
                INSERT INTO shipping_address
                    (member_id, label, receiver_name, phone, postal_code, address, is_default)
                SELECT id, 'Test', 'Integration Test', '0900000000', '100', 'Test Address', TRUE
                FROM member
                WHERE email = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM shipping_address sa WHERE sa.member_id = member.id
                  )
                """, email);
    }
}
