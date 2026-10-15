package dev.webhooks.platform.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Provides the test database. If {@code TEST_DATABASE_URL} is set (with optional
 * {@code TEST_DATABASE_USERNAME} / {@code TEST_DATABASE_PASSWORD}) that PostgreSQL instance is
 * used; otherwise a PostgreSQL 16 Testcontainer is started once per JVM. Either way the tests run
 * against real PostgreSQL, which the locking and constraint behaviour under test depends on.
 */
public class PostgresInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static PostgreSQLContainer container;

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String url = System.getenv("TEST_DATABASE_URL");
        String username = System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "webhooks");
        String password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "webhooks");
        if (url == null || url.isBlank()) {
            PostgreSQLContainer started = container();
            url = started.getJdbcUrl();
            username = started.getUsername();
            password = started.getPassword();
        }
        TestPropertyValues.of(
                "spring.datasource.url=" + url,
                "spring.datasource.username=" + username,
                "spring.datasource.password=" + password,
                "spring.flyway.clean-disabled=false")
                .applyTo(context.getEnvironment());
    }

    private static synchronized PostgreSQLContainer container() {
        if (container == null) {
            container = new PostgreSQLContainer("postgres:16-alpine");
            container.start();
        }
        return container;
    }
}
