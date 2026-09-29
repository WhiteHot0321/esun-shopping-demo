package com.esun.shop.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One-time, opt-in adoption of the exact legacy initdb schema as Flyway V1. */
@Configuration
public class FlywayAdoptionConfiguration {

    // Generated from MySQL 8.0's execution of the complete DB/ initdb chain. Keep this
    // pinned to that trusted fixture; never learn a fingerprint from the candidate database.
    static final String SUPPORTED_LEGACY_FINGERPRINT = "1c5ee00817f71420e5b09983bdf9fb1fc572b6c4ace97a0a32d91ba77b2ae23c";

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(DataSource dataSource,
            @Value("${shop.flyway.adopt-existing:false}") boolean adoptExisting) {
        return flyway -> {
            if (adoptExisting && hasNoHistoryTable(dataSource) && hasApplicationTables(dataSource)) {
                if (!MigrationVersion.fromVersion("1").equals(flyway.getConfiguration().getBaselineVersion())) {
                    throw new FlywayException("Existing schema adoption requires Flyway baseline version 1");
                }
                assertSupportedBaseline(dataSource);
                flyway.baseline();
            }
            flyway.migrate();
        };
    }

    static void assertSupportedBaseline(DataSource dataSource) {
        if (!SUPPORTED_LEGACY_FINGERPRINT.equals(schemaFingerprint(dataSource))) {
            throw new FlywayException("Unsupported existing schema: its structural fingerprint does not match the supported legacy baseline");
        }
    }

    public static String schemaFingerprint(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String schema = connection.getCatalog();
            if (schema == null || schema.isBlank()) {
                throw new FlywayException("Could not identify the existing schema");
            }
            assertMetadataVisibility(connection, schema);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digestRows(connection, digest, schema, "tables", """
                    SELECT CONCAT_WS('|', table_name, table_type, COALESCE(engine, ''),
                        COALESCE(table_collation, ''))
                    FROM information_schema.tables WHERE table_schema = ?
                    ORDER BY table_name""");
            digestRows(connection, digest, schema, "columns", """
                    SELECT CONCAT_WS('|', table_name, ordinal_position, column_name, column_type, is_nullable,
                        COALESCE(column_default, '<NULL>'), extra, COALESCE(generation_expression, ''),
                        COALESCE(character_set_name, ''), COALESCE(collation_name, ''))
                    FROM information_schema.columns WHERE table_schema = ? ORDER BY table_name, ordinal_position""");
            digestRows(connection, digest, schema, "indexes", """
                    SELECT CONCAT_WS('|', table_name, index_name, non_unique, seq_in_index, COALESCE(column_name, ''),
                        COALESCE(expression, ''), COALESCE(collation, ''), COALESCE(sub_part, ''), COALESCE(index_type, ''))
                    FROM information_schema.statistics WHERE table_schema = ? ORDER BY table_name, index_name, seq_in_index""");
            digestRows(connection, digest, schema, "constraints", """
                    SELECT CONCAT_WS('|', table_name, constraint_name, constraint_type, COALESCE(enforced, ''))
                    FROM information_schema.table_constraints WHERE constraint_schema = ? ORDER BY table_name, constraint_name""");
            digestRows(connection, digest, schema, "constraint-columns", """
                    SELECT CONCAT_WS('|', table_name, constraint_name, ordinal_position, column_name,
                        COALESCE(referenced_table_schema, ''), COALESCE(referenced_table_name, ''),
                        COALESCE(referenced_column_name, ''))
                    FROM information_schema.key_column_usage WHERE constraint_schema = ?
                    ORDER BY table_name, constraint_name, ordinal_position""");
            digestRows(connection, digest, schema, "checks", """
                    SELECT CONCAT_WS('|', tc.table_name, cc.constraint_name, cc.check_clause)
                    FROM information_schema.check_constraints cc JOIN information_schema.table_constraints tc
                    ON tc.constraint_schema = cc.constraint_schema AND tc.constraint_name = cc.constraint_name
                    WHERE cc.constraint_schema = ? ORDER BY tc.table_name, cc.constraint_name""");
            digestRows(connection, digest, schema, "foreign-key-rules", """
                    SELECT CONCAT_WS('|', constraint_name, update_rule, delete_rule, match_option, unique_constraint_name)
                    FROM information_schema.referential_constraints WHERE constraint_schema = ? ORDER BY constraint_name""");
            digestRows(connection, digest, schema, "routines", """
                    SELECT CONCAT_WS('|', routine_name, routine_type, sql_data_access, is_deterministic, security_type,
                        sql_mode, character_set_client, collation_connection, database_collation,
                        routine_definition)
                    FROM information_schema.routines WHERE routine_schema = ? ORDER BY routine_name, routine_type""");
            digestRows(connection, digest, schema, "routine-parameters", """
                    SELECT CONCAT_WS('|', specific_name, ordinal_position, COALESCE(parameter_mode, ''),
                        COALESCE(parameter_name, ''), COALESCE(data_type, ''), COALESCE(dtd_identifier, ''),
                        COALESCE(character_set_name, ''), COALESCE(collation_name, ''))
                    FROM information_schema.parameters WHERE specific_schema = ?
                    ORDER BY specific_name, ordinal_position""");
            digestRows(connection, digest, schema, "triggers", """
                    SELECT CONCAT_WS('|', trigger_name, event_manipulation, event_object_table,
                        action_statement, action_timing, sql_mode, character_set_client,
                        collation_connection, database_collation)
                    FROM information_schema.triggers WHERE trigger_schema = ? ORDER BY trigger_name""");
            digestRows(connection, digest, schema, "events", """
                    SELECT CONCAT_WS('|', event_name, event_definition, event_type, execute_at,
                        interval_value, interval_field, status, sql_mode, character_set_client,
                        collation_connection, database_collation)
                    FROM information_schema.events WHERE event_schema = ? ORDER BY event_name""");
            return HexFormat.of().formatHex(digest.digest());
        } catch (SQLException | NoSuchAlgorithmException exception) {
            throw new FlywayException("Could not inspect existing schema", exception);
        }
    }

    private static void assertMetadataVisibility(Connection connection, String schema) throws SQLException {
        try (PreparedStatement visibility = connection.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.routines
                WHERE routine_schema = ? AND routine_definition IS NULL""")) {
            visibility.setString(1, schema);
            try (ResultSet result = visibility.executeQuery()) {
                result.next();
                if (result.getInt(1) != 0) {
                    throw new FlywayException("Existing schema routine definitions are not visible; grant SHOW_ROUTINE before adoption");
                }
            }
        }
        // TRIGGER and EVENT metadata is hidden without those grants, even when objects exist.
        // Require direct grants so an apparently empty result cannot be trusted by accident.
        try (PreparedStatement privileges = connection.prepareStatement("""
                SELECT COUNT(DISTINCT privilege_type) FROM (
                    SELECT privilege_type FROM information_schema.schema_privileges
                    WHERE REPLACE(table_schema, CHAR(92), '') = ?
                    UNION ALL
                    SELECT privilege_type FROM information_schema.user_privileges
                ) p WHERE privilege_type IN ('TRIGGER', 'EVENT')""")) {
            privileges.setString(1, schema);
            try (ResultSet result = privileges.executeQuery()) {
                result.next();
                if (result.getInt(1) != 2) {
                    throw new FlywayException("Existing schema trigger/event metadata is not fully visible; grant TRIGGER and EVENT before adoption");
                }
            }
        }
    }

    private static void digestRows(Connection connection, MessageDigest digest, String schema, String section, String sql)
            throws SQLException {
        digest.update((section + "\n").getBytes(StandardCharsets.UTF_8));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String value = rows.getString(1);
                    if (value == null) {
                        throw new FlywayException("Existing schema metadata is not fully visible: " + section);
                    }
                    digest.update(value.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                }
            }
        }
    }

    private static boolean hasNoHistoryTable(DataSource dataSource) {
        return !tableExists(dataSource, "flyway_schema_history");
    }

    private static boolean hasApplicationTables(DataSource dataSource) {
        return tableExists(dataSource, "product") || tableExists(dataSource, "shop_order") || tableExists(dataSource, "member");
    }

    private static boolean tableExists(DataSource dataSource, String table) {
        try (Connection connection = dataSource.getConnection();
                ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[] {"TABLE"})) {
            return result.next();
        } catch (SQLException exception) {
            throw new FlywayException("Could not inspect existing schema", exception);
        }
    }
}
