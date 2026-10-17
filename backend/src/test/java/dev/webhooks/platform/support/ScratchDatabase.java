package dev.webhooks.platform.support;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A throw-away database on the same PostgreSQL server as the test context, for tests that must not
 * touch the shared schema (migration upgrades, seeded query plans). Needs CREATEDB, which the
 * Testcontainers user has.
 */
public final class ScratchDatabase implements AutoCloseable {

    private static final Pattern URL = Pattern.compile("(jdbc:postgresql://[^/]+/)([^?]+)(.*)");

    private final JdbcClient admin;
    private final String name;
    private final DriverManagerDataSource dataSource;

    public ScratchDatabase(DataSource adminDataSource, Environment env, String prefix) {
        this.admin = JdbcClient.create(adminDataSource);
        this.name = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        admin.sql("CREATE DATABASE " + name).update();
        Matcher m = URL.matcher(env.getRequiredProperty("spring.datasource.url"));
        if (!m.matches()) {
            throw new IllegalStateException("unexpected datasource url");
        }
        this.dataSource = new DriverManagerDataSource(m.group(1) + name + m.group(3),
                env.getProperty("spring.datasource.username"), env.getProperty("spring.datasource.password"));
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /** Applies the real migrations up to {@code target} ("latest" for all). */
    public void migrateTo(String target) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target)).load().migrate();
    }

    public Flyway flyway() {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
    }

    @Override
    public void close() {
        admin.sql("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)").update();
    }
}
