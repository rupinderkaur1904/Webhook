package dev.webhooks.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.PlatformProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Starts the real application the way production does (default profile, configuration from environment
 * variables only) once with a valid configuration and once per invalid mandatory setting. Each invalid
 * setting must stop the process with a message that names the variable and says what is wrong, without
 * echoing a secret value. The startup output of each case is written to
 * {@code target/audit-startup-failures.txt}.
 */
@IntegrationTest
class AuditStartupConfigTest {

    @Autowired Environment env;

    record Case(String name, Map<String, String> overrides, List<String> removed, List<String> expected, String secretValue) {}

    @Test
    void invalidMandatorySettingsStopStartupWithAClearMessage() throws Exception {
        String key = randomKey(32);
        String tooShortToken = "audit-short-metrics-tkn"; // 23 characters; the minimum is 24
        String shortKey = randomKey(16);
        Map<String, String> valid = new HashMap<>(Map.of(
                "DATABASE_URL", env.getRequiredProperty("spring.datasource.url"),
                "DATABASE_USERNAME", env.getRequiredProperty("spring.datasource.username"),
                "DATABASE_PASSWORD", env.getRequiredProperty("spring.datasource.password"),
                "SECRET_ENCRYPTION_KEY", key,
                "METRICS_TOKEN", "audit-metrics-token-0123456789abcdef",
                // The shared test database holds other tests' queue; this process must not deliver it.
                "WEBHOOKS_WORKER_ENABLED", "false",
                "JAVA_TOOL_OPTIONS", "-Xmx256m"));

        List<Case> cases = List.of(
                new Case("missing SECRET_ENCRYPTION_KEY", Map.of(), List.of("SECRET_ENCRYPTION_KEY"),
                        List.of("SECRET_ENCRYPTION_KEY"), null),
                new Case("SECRET_ENCRYPTION_KEY not base64", Map.of("SECRET_ENCRYPTION_KEY", "not*base64*key!"), List.of(),
                        List.of("encryption-key", "base64"), "not*base64*key!"),
                new Case("SECRET_ENCRYPTION_KEY of 16 bytes", Map.of("SECRET_ENCRYPTION_KEY", shortKey), List.of(),
                        List.of("encryption-key", "32 bytes"), shortKey),
                new Case("METRICS_TOKEN shorter than 24 characters", Map.of("METRICS_TOKEN", tooShortToken), List.of(),
                        List.of("METRICS_TOKEN", "24 characters"), tooShortToken),
                new Case("TRUSTED_PROXIES with a host name", Map.of("TRUSTED_PROXIES", "10.0.0.0/8,fly-proxy.internal"), List.of(),
                        List.of("trusted-proxies", "fly-proxy.internal", "not an IP address or CIDR range"), null),
                new Case("BOOTSTRAP_PASSWORD shorter than 12 characters",
                        Map.of("BOOTSTRAP_EMAIL", "ops@example.com", "BOOTSTRAP_PASSWORD", "short-pw"), List.of(),
                        List.of("bootstrap.password", "at least 12 characters"), "short-pw"),
                new Case("BOOTSTRAP_EMAIL without BOOTSTRAP_PASSWORD", Map.of("BOOTSTRAP_EMAIL", "ops@example.com"), List.of(),
                        List.of("bootstrap.password", "at least 12 characters"), null),
                new Case("lease shorter than connect + response timeout + 5s",
                        Map.of("WEBHOOKS_WORKER_LEASE_DURATION", "10s"), List.of(),
                        List.of("lease-duration", "connect-timeout + response-timeout"), null));

        PlatformProcess baseline = PlatformProcess.start(List.of(), valid, List.of());
        Map<Case, PlatformProcess> started = new LinkedHashMap<>();
        StringBuilder report = new StringBuilder();
        try {
            for (Case c : cases) {
                Map<String, String> envVars = new HashMap<>(valid);
                c.removed().forEach(envVars::remove);
                envVars.putAll(c.overrides());
                started.put(c, PlatformProcess.start(List.of(), envVars, List.of()));
            }

            baseline.awaitHealthy(Duration.ofSeconds(120));
            assertThat(baseline.stop(Duration.ofSeconds(30))).as("valid configuration: clean shutdown").isIn(0, 143);

            List<String> problems = new ArrayList<>();
            for (var entry : started.entrySet()) {
                Case c = entry.getKey();
                PlatformProcess p = entry.getValue();
                Integer exit = p.awaitExit(Duration.ofSeconds(120));
                String output = p.output();
                report.append("=== ").append(c.name()).append(" (exit ").append(exit).append(")\n")
                        .append(excerpt(output)).append("\n\n");
                if (exit == null || exit == 0) {
                    problems.add(c.name() + ": process did not fail (exit " + exit + ")");
                    continue;
                }
                for (String expected : c.expected()) {
                    if (!output.contains(expected)) {
                        problems.add(c.name() + ": output does not mention '" + expected + "'");
                    }
                }
                if (c.secretValue() != null && output.contains(c.secretValue())) {
                    problems.add(c.name() + ": the rejected secret value is printed in the startup output");
                }
            }
            Files.writeString(Path.of("target", "audit-startup-failures.txt"), report.toString());
            assertThat(problems).as("startup failure messages (see target/audit-startup-failures.txt)").isEmpty();
        } finally {
            baseline.close();
            started.values().forEach(PlatformProcess::close);
        }
    }

    /** The operator-facing part: Spring Boot's failure report, or the last exception message. */
    private static String excerpt(String output) {
        int report = output.indexOf("APPLICATION FAILED TO START");
        if (report >= 0) {
            return output.substring(Math.max(0, report - 200), Math.min(output.length(), report + 1500));
        }
        int caused = output.lastIndexOf("Caused by:");
        if (caused >= 0) {
            return output.substring(caused, Math.min(output.length(), caused + 600));
        }
        return output.length() > 2000 ? output.substring(output.length() - 2000) : output;
    }

    private static String randomKey(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }
}
