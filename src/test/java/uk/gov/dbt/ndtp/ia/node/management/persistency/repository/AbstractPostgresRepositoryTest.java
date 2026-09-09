/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for repository tests that need the real Flyway migrations and real
 * Postgres behaviour (partial unique indexes, {@code plpgsql} triggers) that the
 * project's shared H2 test profile ({@code src/test/resources/application.yml}) cannot
 * provide. Points the Spring context at a shared Postgres container and re-enables
 * Flyway (disabled in the shared profile) so migrations apply for real.
 *
 * <p>The container is started eagerly in a static initializer rather than left to the
 * {@code @Testcontainers}/{@code @Container} JUnit extension. With multiple concrete
 * subclasses - each getting its own Spring context - relying on the extension's
 * per-class {@code beforeAll} to start (or no-op past) the container raced against
 * context refresh on CI and on some local Docker setups: the first class or two would
 * see the container "started" but not yet accepting TCP connections, and every test in
 * that class would time out. Starting synchronously here, before any JUnit lifecycle
 * callback runs for any subclass, removes that race. Testcontainers' Ryuk reaper still
 * cleans the container up at JVM exit; it is not tied to the JUnit5 extension.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractPostgresRepositoryTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
    }
}
