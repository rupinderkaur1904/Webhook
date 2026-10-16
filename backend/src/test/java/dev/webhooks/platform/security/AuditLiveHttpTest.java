package dev.webhooks.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.support.PostgresInitializer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * Audit (C9) over a raw socket against the embedded server (same configuration as
 * DashboardServingTest, so the context is shared): requests a normal client library would refuse to
 * send, such as malformed paths the firewall rejects and TRACE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = PostgresInitializer.class)
class AuditLiveHttpTest {

    @LocalServerPort int port;

    record Raw(int status, List<String> headers, String body) {
        String header(String name) {
            return headers.stream().filter(h -> h.regionMatches(true, 0, name + ":", 0, name.length() + 1))
                    .map(h -> h.substring(name.length() + 1).trim()).findFirst().orElse(null);
        }
    }

    Raw send(String method, String target) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = in.readLine();
            List<String> headers = new ArrayList<>();
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                headers.add(line);
            }
            StringBuilder body = new StringBuilder();
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                body.append(line).append('\n');
            }
            return new Raw(Integer.parseInt(statusLine.split(" ")[1]), headers, body.toString());
        }
    }

    /** Rejected by Spring Security's firewall: a 400 problem with the request id, not a misleading 401. */
    @Test
    void firewallRejectionsAreA400Problem() throws Exception {
        for (String[] r : new String[][] {{"GET", "/api/v1//endpoints"}, {"GET", "/api/v1/endpoints;jsessionid=x"},
                {"GET", "/api/v1/%2e%2e/actuator/env"}}) {
            Raw raw = send(r[0], r[1]);
            String what = r[0] + " " + r[1] + " -> " + raw.status() + " " + raw.headers() + " " + raw.body();
            assertThat(raw.status()).as(what).isEqualTo(400);
            assertThat(raw.header("Content-Type")).as(what).startsWith("application/problem+json");
            assertThat(raw.header("X-Content-Type-Options")).as(what).isEqualTo("nosniff");
            assertThat(raw.body()).as(what).contains("\"code\":\"REQUEST_REJECTED\"")
                    .contains("\"requestId\":\"" + raw.header("X-Request-Id") + "\"");
        }
    }

    /** Container errors keep their real status (TRACE is 405, not a 401); /error itself is not a public page. */
    @Test
    void containerErrorsKeepTheirStatus() throws Exception {
        Raw trace = send("TRACE", "/api/v1/endpoints");
        assertThat(trace.status()).as(trace.body()).isEqualTo(405);
        assertThat(trace.body()).doesNotContain("Exception").doesNotContain("trace\"");
        assertThat(send("GET", "/error").status()).isEqualTo(401);
    }

    /** Rejected by Tomcat before the application sees them: a bare 400 that names no server version. */
    @Test
    void connectorRejectionsRevealNothing() throws Exception {
        for (String target : List.of("/api/v1/endpoints/..%2f..%2factuator%2fenv", "/api/v1/endpoints%00",
                "/api/v1/endpoints/%5c..%5c")) {
            Raw raw = send("GET", target);
            assertThat(raw.status()).as(target).isEqualTo(400);
            assertThat(raw.body()).as(target).doesNotContainIgnoringCase("tomcat").doesNotContain("Exception");
            assertThat(raw.header("Server")).as(target).isNull();
        }
    }
}
