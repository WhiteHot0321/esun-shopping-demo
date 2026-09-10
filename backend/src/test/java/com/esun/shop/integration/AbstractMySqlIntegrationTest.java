package com.esun.shop.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Shared real-MySQL fixture for the Testcontainers integration tests.
 *
 * Loads the actual backend/DB/01_schema.sql -> 02_data.sql -> 03_stored_procedures.sql
 * scripts (the same files docker-compose.yml mounts into /docker-entrypoint-initdb.d)
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

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("esun_shop")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("DB/01_schema.sql"), "/docker-entrypoint-initdb.d/01_schema.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("DB/02_data.sql"), "/docker-entrypoint-initdb.d/02_data.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("DB/03_stored_procedures.sql"), "/docker-entrypoint-initdb.d/03_stored_procedures.sql");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
