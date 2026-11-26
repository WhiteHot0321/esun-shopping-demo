package com.esun.shop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Task 088 §4.4: proves Boot's own {@code FlywayAutoConfiguration} is active (Boot 4 moved it into
 * {@code spring-boot-flyway}, pulled in by {@code spring-boot-starter-flyway}). {@link FlywayAdoptionIntegrationTest}
 * never starts Spring, so it cannot notice a missing starter; this test goes through the Boot path only.
 *
 * <p>Verified red by removing {@code spring-boot-starter-flyway} from the pom (see docs/tasks/088).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=true", "stock.redis.enabled=false", "llm.indexing.enabled=false"})
class FlywayAutoConfigurationWiringIntegrationTest {

    @Container
    static final MySQLContainer<?> EMPTY = WiringFixtures.mysql();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        WiringFixtures.datasource(registry, EMPTY);
    }

    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test
    void bootAutoConfigurationCreatesFlywayAndAppliesBothMigrationsToAnEmptySchema() {
        assertThat(context.getBeansOfType(Flyway.class)).hasSize(1);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = 1 AND type = 'SQL' ORDER BY installed_rank",
                String.class)).containsExactly("1", "2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = DATABASE() "
                + "AND routine_type = 'PROCEDURE' AND routine_name IN ('sp_add_product', 'sp_get_available_products', 'sp_decrease_stock')",
                Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'member' AND column_name IN ('status', 'token_version')", Integer.class)).isEqualTo(2);
    }
}

/** Second case: the {@code FlywayMigrationStrategy} bean must be adopted by Boot 4's auto-configuration (baseline path). */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=true", "shop.flyway.adopt-existing=true", "stock.redis.enabled=false", "llm.indexing.enabled=false"})
class FlywayAutoConfigurationAdoptionWiringIntegrationTest {

    @Container
    static final MySQLContainer<?> LEGACY = WiringFixtures.mysql(
            "01_schema", "02_data", "03_stored_procedures", "04_add_order_request", "04_faq", "04_member",
            "05_password_reset_token", "06_member_profile", "07_shipping_address", "08_shopping_cart",
            "09_product_review", "10_product_management", "11_order_status", "12_audit_log", "13_payment",
            "14_coupon", "15_recommendation");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // The adoption fingerprint reads routine definitions, which needs SHOW_ROUTINE (same grant as FlywayAdoptionIntegrationTest).
        new JdbcTemplate(new DriverManagerDataSource(LEGACY.getJdbcUrl(), "root", LEGACY.getPassword()))
                .execute("GRANT SHOW_ROUTINE ON *.* TO 'test'@'%'");
        WiringFixtures.datasource(registry, LEGACY);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void legacySchemaIsBaselinedThroughTheSpringStrategyBeanAndThenMigratedToV2() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(history).extracting(row -> row.get("version") + ":" + row.get("type")).containsExactly("1:BASELINE", "2:SQL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity FROM product WHERE product_id = 'P001'", Integer.class)).isEqualTo(5);
    }
}

final class WiringFixtures {

    private WiringFixtures() {
    }

    static MySQLContainer<?> mysql(String... initScripts) {
        MySQLContainer<?> container = new MySQLContainer<>("mysql:8.0").withDatabaseName("esun_shop")
                .withUsername("test").withPassword("test")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci",
                        "--character-set-client-handshake=FALSE");
        for (String script : initScripts) {
            container.withCopyFileToContainer(MountableFile.forHostPath("DB/" + script + ".sql"),
                    "/docker-entrypoint-initdb.d/" + script + ".sql");
        }
        return container;
    }

    static void datasource(DynamicPropertyRegistry registry, MySQLContainer<?> container) {
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }
}
