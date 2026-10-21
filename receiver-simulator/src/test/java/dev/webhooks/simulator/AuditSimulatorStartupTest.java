package dev.webhooks.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Audit (C10): real, isolated application contexts for each start-up configuration. */
@ExtendWith(OutputCaptureExtension.class)
class AuditSimulatorStartupTest {

    static ConfigurableApplicationContext start(String... args) {
        String[] all = new String[args.length + 1];
        all[0] = "--server.port=0";
        System.arraycopy(args, 0, all, 1, args.length);
        return new SpringApplication(SimulatorApplication.class).run(all);
    }

    static int get(ConfigurableApplicationContext ctx, String path, String auth) throws Exception {
        int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
                .send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void noTokenAndNoOverrideFailsToStart() {
        assertThatThrownBy(() -> start("--simulator.control-token=", "--simulator.open-control-api=false"))
                .hasStackTraceContaining("SIMULATOR_CONTROL_TOKEN is not set");
    }

    @Test
    void blankTokenCountsAsNoToken() {
        assertThatThrownBy(() -> start("--simulator.control-token=   "))
                .hasStackTraceContaining("SIMULATOR_CONTROL_TOKEN is not set");
    }

    @Test
    void noTokenWithOverrideStartsOpenAndWarns(CapturedOutput output) throws Exception {
        try (var ctx = start("--simulator.control-token=", "--simulator.open-control-api=true")) {
            assertThat(output).contains("SIMULATOR_OPEN_CONTROL_API=true and no SIMULATOR_CONTROL_TOKEN");
            assertThat(get(ctx, "/receivers/open-start/requests", null)).isEqualTo(200);
        }
    }

    @Test
    void tokenSetStartsAndProtectsTheControlApi(CapturedOutput output) throws Exception {
        try (var ctx = start("--simulator.control-token=audit-token-0123456789abcdef", "--simulator.open-control-api=false")) {
            assertThat(output).doesNotContain("the control API is open").doesNotContain("audit-token-0123456789abcdef");
            assertThat(get(ctx, "/healthz", null)).isEqualTo(200);
            assertThat(get(ctx, "/receivers/x/requests", null)).isEqualTo(401);
            assertThat(get(ctx, "/receivers/x/requests", "Bearer audit-token-0123456789abcdef")).isEqualTo(200);
        }
    }
}
