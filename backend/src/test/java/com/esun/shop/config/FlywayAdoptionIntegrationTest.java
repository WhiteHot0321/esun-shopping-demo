package com.esun.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class FlywayAdoptionIntegrationTest {

    @Container
    static final MySQLContainer<?> EMPTY = mysql();

    // The official mysql image executes this exact legacy initdb chain; no application-side SQL splitting is used.
    @Container
    static final MySQLContainer<?> LEGACY = mysql()
            .withCopyFileToContainer(MountableFile.forHostPath("DB/01_schema.sql"), "/docker-entrypoint-initdb.d/01_schema.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/02_data.sql"), "/docker-entrypoint-initdb.d/02_data.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/03_stored_procedures.sql"), "/docker-entrypoint-initdb.d/03_stored_procedures.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/04_add_order_request.sql"), "/docker-entrypoint-initdb.d/04_add_order_request.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/04_faq.sql"), "/docker-entrypoint-initdb.d/04_faq.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/04_member.sql"), "/docker-entrypoint-initdb.d/04_member.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/05_password_reset_token.sql"), "/docker-entrypoint-initdb.d/05_password_reset_token.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/06_member_profile.sql"), "/docker-entrypoint-initdb.d/06_member_profile.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/07_shipping_address.sql"), "/docker-entrypoint-initdb.d/07_shipping_address.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/08_shopping_cart.sql"), "/docker-entrypoint-initdb.d/08_shopping_cart.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/09_product_review.sql"), "/docker-entrypoint-initdb.d/09_product_review.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/10_product_management.sql"), "/docker-entrypoint-initdb.d/10_product_management.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/11_order_status.sql"), "/docker-entrypoint-initdb.d/11_order_status.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/12_audit_log.sql"), "/docker-entrypoint-initdb.d/12_audit_log.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/13_payment.sql"), "/docker-entrypoint-initdb.d/13_payment.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/14_coupon.sql"), "/docker-entrypoint-initdb.d/14_coupon.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/15_recommendation.sql"), "/docker-entrypoint-initdb.d/15_recommendation.sql");

    @Container
    static final MySQLContainer<?> INVALID = mysql()
            .withCopyFileToContainer(MountableFile.forHostPath("DB/01_schema.sql"), "/docker-entrypoint-initdb.d/01_schema.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/02_data.sql"), "/docker-entrypoint-initdb.d/02_data.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/03_stored_procedures.sql"), "/docker-entrypoint-initdb.d/03_stored_procedures.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/04_add_order_request.sql"), "/docker-entrypoint-initdb.d/04_add_order_request.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/04_faq.sql"), "/docker-entrypoint-initdb.d/04_faq.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/04_member.sql"), "/docker-entrypoint-initdb.d/04_member.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/05_password_reset_token.sql"), "/docker-entrypoint-initdb.d/05_password_reset_token.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/06_member_profile.sql"), "/docker-entrypoint-initdb.d/06_member_profile.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/07_shipping_address.sql"), "/docker-entrypoint-initdb.d/07_shipping_address.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/08_shopping_cart.sql"), "/docker-entrypoint-initdb.d/08_shopping_cart.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/09_product_review.sql"), "/docker-entrypoint-initdb.d/09_product_review.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/10_product_management.sql"), "/docker-entrypoint-initdb.d/10_product_management.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/11_order_status.sql"), "/docker-entrypoint-initdb.d/11_order_status.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/12_audit_log.sql"), "/docker-entrypoint-initdb.d/12_audit_log.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/13_payment.sql"), "/docker-entrypoint-initdb.d/13_payment.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/14_coupon.sql"), "/docker-entrypoint-initdb.d/14_coupon.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("DB/15_recommendation.sql"), "/docker-entrypoint-initdb.d/15_recommendation.sql");

    @Test
    void migratesEmptySchemaAndAdoptsOnlyExactLegacyStructureWithoutChangingData() {
        Flyway emptyFlyway = flyway(dataSource(EMPTY));
        assertThat(emptyFlyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(emptyFlyway.migrate().migrationsExecuted).isZero();
        assertThat(routineCount(jdbc(EMPTY))).isEqualTo(3);

        JdbcTemplate invalidJdbc = jdbc(INVALID);
        JdbcTemplate invalidFixtureAdmin = jdbcAsRoot(INVALID);
        // Root changes only disposable fixtures; adoption always uses the restricted account.
        assertThatThrownBy(() -> new FlywayAdoptionConfiguration()
                .flywayMigrationStrategy(dataSource(INVALID), true).migrate(flyway(dataSource(INVALID))))
                .isInstanceOf(FlywayException.class).hasMessageContaining("routine definitions are not visible");
        assertThat(invalidJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'", Integer.class)).isZero();
        grantRoutineVisibility(invalidFixtureAdmin);
        assertRejectedMutation(invalidJdbc, invalidFixtureAdmin,
                "ALTER TABLE shopping_cart DROP INDEX uk_shopping_cart_member_product",
                "ALTER TABLE shopping_cart ADD CONSTRAINT uk_shopping_cart_member_product UNIQUE (member_id, product_id)");
        assertRejectedMutation(invalidJdbc, invalidFixtureAdmin,
                "ALTER TABLE shipping_address MODIFY default_member_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_default THEN member_id + 1 ELSE NULL END) STORED",
                "ALTER TABLE shipping_address MODIFY default_member_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_default THEN member_id ELSE NULL END) STORED");
        assertRejectedMutation(invalidJdbc, invalidFixtureAdmin,
                "ALTER TABLE product ALTER CHECK product_chk_1 NOT ENFORCED",
                "ALTER TABLE product ALTER CHECK product_chk_1 ENFORCED");
        assertRejectedMutation(invalidJdbc, invalidFixtureAdmin,
                "ALTER TABLE product_image MODIFY image_url VARCHAR(499) NOT NULL",
                "ALTER TABLE product_image MODIFY image_url VARCHAR(500) NOT NULL");
        String originalRoutine = invalidFixtureAdmin.queryForObject(
                "SHOW CREATE PROCEDURE sp_get_available_products", (rs, row) -> rs.getString("Create Procedure"));
        invalidFixtureAdmin.execute("DROP PROCEDURE sp_get_available_products");
        assertRejected(invalidJdbc);
        invalidFixtureAdmin.execute(originalRoutine);
        invalidFixtureAdmin.execute("DROP PROCEDURE sp_get_available_products");
        assertThat(originalRoutine).contains("`sp_get_available_products`()");
        invalidFixtureAdmin.execute(originalRoutine.replace("`sp_get_available_products`()",
                "`sp_get_available_products`(IN unexpected_arg INT)"));
        assertRejected(invalidJdbc);
        invalidFixtureAdmin.execute("DROP PROCEDURE sp_get_available_products");
        invalidFixtureAdmin.execute(originalRoutine);
        invalidFixtureAdmin.execute("DROP PROCEDURE sp_get_available_products");
        assertThat(originalRoutine).contains("quantity > 0");
        invalidFixtureAdmin.execute(originalRoutine.replace("quantity > 0", "quantity >= 0"));
        assertRejected(invalidJdbc);

        JdbcTemplate legacyJdbc = jdbc(LEGACY);
        grantRoutineVisibility(jdbcAsRoot(LEGACY));
        assertThat(legacyJdbc.queryForObject("SELECT COUNT(*) FROM product WHERE product_id = 'P001'", Integer.class)).isEqualTo(1);
        assertThat(FlywayAdoptionConfiguration.schemaFingerprint(dataSource(LEGACY)))
                .isEqualTo(FlywayAdoptionConfiguration.SUPPORTED_LEGACY_FINGERPRINT);

        Flyway legacyFlyway = flyway(dataSource(LEGACY));
        new FlywayAdoptionConfiguration().flywayMigrationStrategy(dataSource(LEGACY), true).migrate(legacyFlyway);
        assertThat(legacyJdbc.queryForObject("SELECT quantity FROM product WHERE product_id = 'P001'", Integer.class)).isEqualTo(5);
        assertThat(legacyJdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version = '1'", Integer.class)).isEqualTo(1);

    }

    private static MySQLContainer<?> mysql() {
        return new MySQLContainer<>("mysql:8.0").withDatabaseName("esun_shop").withUsername("test").withPassword("test")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci", "--character-set-client-handshake=FALSE");
    }

    private static int routineCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = DATABASE() AND routine_type = 'PROCEDURE' AND routine_name IN ('sp_add_product', 'sp_get_available_products', 'sp_decrease_stock')", Integer.class);
    }

    private static Flyway flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").baselineOnMigrate(false).load();
    }

    private static DataSource dataSource(MySQLContainer<?> container) {
        return new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static JdbcTemplate jdbc(MySQLContainer<?> container) {
        return new JdbcTemplate(dataSource(container));
    }

    private static JdbcTemplate jdbcAsRoot(MySQLContainer<?> container) {
        return new JdbcTemplate(new DriverManagerDataSource(container.getJdbcUrl(), "root", container.getPassword()));
    }

    private static void grantRoutineVisibility(JdbcTemplate admin) {
        admin.execute("GRANT SHOW_ROUTINE ON *.* TO 'test'@'%'");
    }

    private static void assertRejectedMutation(JdbcTemplate app, JdbcTemplate admin, String mutation, String restore) {
        admin.execute(mutation);
        try {
            assertRejected(app);
        } finally {
            admin.execute(restore);
        }
    }

    private static void assertRejected(JdbcTemplate app) {
        DataSource source = app.getDataSource();
        assertThatThrownBy(() -> new FlywayAdoptionConfiguration().flywayMigrationStrategy(source, true)
                .migrate(flyway(source)))
                .isInstanceOf(FlywayException.class).hasMessageContaining("Unsupported existing schema");
        assertThat(app.queryForObject("SELECT quantity FROM product WHERE product_id = 'P001'", Integer.class)).isEqualTo(5);
        assertThat(app.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'", Integer.class)).isZero();
    }
}
