package dev.webhooks.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Audit (C10): the control token over real HTTP. Requests are written byte for byte on a socket, so
 * no client library normalises the path before the server sees it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"simulator.control-token=" + AuditSimulatorAuthTest.TOKEN, "simulator.max-receivers=50"})
class AuditSimulatorAuthTest {

    static final String TOKEN = "audit-control-token-0123456789";
    static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @LocalServerPort int port;

    record Reply(int status, String body) {}

    Reply raw(String method, String target, String authorization, String body) throws Exception {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(10_000);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder()
                    .append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1\r\nConnection: close\r\n");
            if (authorization != null) {
                req.append("Authorization: ").append(authorization).append("\r\n");
            }
            if (body != null) {
                req.append("Content-Type: application/json\r\nContent-Length: ").append(bytes.length).append("\r\n");
            }
            req.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            in.transferTo(all);
            String text = all.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(text.substring(9, 12));
            int split = text.indexOf("\r\n\r\n");
            return new Reply(status, split < 0 ? "" : text.substring(split + 4));
        }
    }

    static final List<String[]> CONTROL_ROUTES = List.of(
            new String[] {"GET", "/receivers/a/config", null},
            new String[] {"PUT", "/receivers/a/config", "{\"status\":500}"},
            new String[] {"GET", "/receivers/a/requests", null},
            new String[] {"DELETE", "/receivers/a", null},
            new String[] {"POST", "/verify", "{\"secret\":\"" + SECRET + "\",\"body\":\"{}\"}"});

    @ParameterizedTest
    @ValueSource(strings = {"", "Bearer", "Bearer ", "Bearer  ", TOKEN, "Basic " + TOKEN, "Bearer wrong",
            "Bearer " + TOKEN + "x", "Bearer audit-control-token-012345678", "Token " + TOKEN, "Bearer" + TOKEN,
            "Bearer\t" + TOKEN, "BearerX " + TOKEN, "Bearer " + TOKEN + " extra"})
    void missingWrongOrMalformedTokensAre401OnEveryControlRoute(String authorization) throws Exception {
        for (String[] route : CONTROL_ROUTES) {
            Reply reply = raw(route[0], route[1], authorization.isEmpty() ? null : authorization, route[2]);
            assertThat(reply.status()).as(route[0] + " " + route[1] + " with [" + authorization + "]").isEqualTo(401);
        }
        assertThat(raw("GET", "/receivers/a/config", "Bearer " + TOKEN, null).body()).contains("\"status\":200");
    }

    /**
     * RFC 7235 makes the scheme case-insensitive and RFC 6750 allows 1*SP before the token, so these
     * spellings of the right token are accepted. Recorded for the audit; not a weakness.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Bearer " + TOKEN, "bearer " + TOKEN, "BEARER " + TOKEN, "Bearer   " + TOKEN, "Bearer " + TOKEN + "  "})
    void correctTokenWorksOnEveryControlRoute(String authorization) throws Exception {
        assertThat(raw("PUT", "/receivers/ok/config", authorization, "{\"status\":201}").status()).isEqualTo(200);
        assertThat(raw("GET", "/receivers/ok/config", authorization, null).status()).isEqualTo(200);
        assertThat(raw("GET", "/receivers/ok/requests", authorization, null).status()).isEqualTo(200);
        assertThat(raw("POST", "/verify", authorization, "{\"secret\":\"" + SECRET + "\",\"body\":\"{}\"}").status()).isEqualTo(200);
        assertThat(raw("DELETE", "/receivers/ok", authorization, null).status()).isEqualTo(204);
    }

    @Test
    void publicRoutesAreOpenAndTheWrongPathHintToo() throws Exception {
        assertThat(raw("POST", "/receivers/pub", null, "{}").status()).isEqualTo(200);
        assertThat(raw("GET", "/healthz", null, null).status()).isEqualTo(200);
        for (String path : List.of("/", "/receivers", "/receivers/")) {
            Reply hint = raw("POST", path, null, "{}");
            assertThat(hint.status()).as(path).isEqualTo(404);
            assertThat(hint.body()).contains("/receivers/{name}");
        }
        // Only POST is public on those paths; request logs (payloads) stay protected.
        assertThat(raw("GET", "/receivers/pub", null, null).status()).isEqualTo(401);
        assertThat(raw("GET", "/receivers/pub/requests", null, null).status()).isEqualTo(401);
        assertThat(raw("GET", "/", null, null).status()).isEqualTo(401);
        raw("DELETE", "/receivers/pub", "Bearer " + TOKEN, null);
    }

    /** Path tricks against the public-route check never reach a control handler without the token. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "POST|/receivers/x/../../verify",
            "POST|/receivers/..;/verify",
            "POST|/receivers/x%2F..%2F..%2Fverify",
            "POST|/receivers/%2e%2e%2fverify",
            "POST|/receivers/..%5Cverify",
            "POST|//verify",
            "POST|/verify;x",
            "POST|/verify/",
            "POST|/receivers/x;/../../verify",
            "PUT|/receivers/x/../a/config",
            "PUT|//receivers/a/config",
            "PUT|/receivers/a;x/config",
            "PUT|/receivers/a/config/",
            "PUT|/receivers/a%2Fconfig",
            "POST|/receivers/a/config",
            "POST|/receivers/a%2Fconfig",
            "GET|/receivers/a/../a/requests",
            "GET|//receivers/a/requests",
            "GET|/receivers/a;x/requests",
            "GET|/receivers/a/requests/",
            "GET|/receivers/a/requests;x",
            "GET|/healthz/../receivers/a/requests",
            "GET|/healthz;/../receivers/a/requests",
            "HEAD|/receivers/a/requests",
            "HEAD|/receivers/a",
            "OPTIONS|/receivers/a/config",
            "OPTIONS|/verify",
            "PATCH|/receivers/a/config",
            "DELETE|/receivers/a/",
            "DELETE|//receivers/a"})
    void pathTricksNeverReachControlRoutesWithoutTheToken(String method, String target) throws Exception {
        // Receiver "a" is configured to answer 202 and has one logged request.
        raw("PUT", "/receivers/a/config", "Bearer " + TOKEN, "{\"status\":202}");
        raw("POST", "/receivers/a", null, "{\"logged\":true}");

        Reply reply = raw(method, target, null, method.equals("GET") || method.equals("HEAD")
                || method.equals("OPTIONS") || method.equals("DELETE") ? null : "{\"status\":500,\"secret\":\"" + SECRET + "\",\"body\":\"{}\"}");

        assertThat(reply.status() / 100).as(method + " " + target + " -> " + reply.status()).isNotEqualTo(2);
        assertThat(reply.body()).doesNotContain("logged").doesNotContain("\"result\"").doesNotContain("\"status\":202");
        // Nothing changed behind the check.
        Reply config = raw("GET", "/receivers/a/config", "Bearer " + TOKEN, null);
        assertThat(config.status()).isEqualTo(200);
        assertThat(config.body()).contains("\"status\":202");
        assertThat(raw("GET", "/receivers/a/requests", "Bearer " + TOKEN, null).body()).contains("logged");
    }

    // ---- /verify ---------------------------------------------------------------------------------------

    static String sign(String secret, String id, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.substring(6)), "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    String verify(String secret, String id, String ts, String sig, String body) throws Exception {
        String json = "{\"secret\":" + quote(secret) + ",\"webhookId\":" + quote(id) + ",\"webhookTimestamp\":" + quote(ts)
                + ",\"webhookSignature\":" + quote(sig) + ",\"body\":" + quote(body) + "}";
        Reply reply = raw("POST", "/verify", "Bearer " + TOKEN, json);
        return reply.status() + " " + reply.body();
    }

    static String quote(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void verifyAcceptsValidAndRejectsEveryAlteration() throws Exception {
        String id = "msg_1";
        String ts = Long.toString(Instant.now().getEpochSecond());
        String body = "{\"a\":\"Zoë 😀\\n\"}";
        String sig = sign(SECRET, id, ts, body);
        String other = "whsec_" + Base64.getEncoder().encodeToString(new byte[32]);

        assertThat(verify(SECRET, id, ts, sig, body)).isEqualTo("200 {\"result\":\"VALID\"}");
        assertThat(verify(SECRET, id, ts, sign(other, id, ts, body) + " " + sig, body)).contains("VALID");
        assertThat(verify(SECRET, id, ts, sig, body + " ")).contains("INVALID_SIGNATURE");
        assertThat(verify(SECRET, "msg_2", ts, sig, body)).contains("INVALID_SIGNATURE");
        assertThat(verify(other, id, ts, sig, body)).contains("INVALID_SIGNATURE");
        assertThat(verify(SECRET, id, ts, "v2," + sig.substring(3), body)).contains("INVALID_SIGNATURE");
        assertThat(verify(SECRET, id, ts, "v1,not-base64!!", body)).contains("INVALID_SIGNATURE");
        String old = Long.toString(Instant.now().getEpochSecond() - 301);
        assertThat(verify(SECRET, id, old, sign(SECRET, id, old, body), body)).contains("TIMESTAMP_OUT_OF_TOLERANCE");
        assertThat(verify(SECRET, id, null, sig, body)).contains("MISSING_HEADERS");
        assertThat(verify(SECRET, id, "abc", sig, body)).contains("MISSING_HEADERS");
        // The response never echoes the secret.
        assertThat(verify(SECRET, id, ts, sig, body)).doesNotContain(SECRET.substring(6));
    }

    /**
     * Audit finding: a malformed secret (not base64, or empty) made /verify, and every delivery to a
     * receiver configured with it, fail with 500. It is now a 400 for the caller who supplied it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"whsec_not base64!!", "", "whsec_"})
    void malformedSecretsAreRejectedNotServerErrors(String secret) throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        assertThat(verify(secret, "id", ts, "v1,AAAA", "{}")).startsWith("400");
        Reply config = raw("PUT", "/receivers/badsecret/config", "Bearer " + TOKEN, "{\"secret\":" + quote(secret) + "}");
        assertThat(config.status()).isEqualTo(400);
        assertThat(raw("POST", "/receivers/badsecret", null, "{}").status()).isEqualTo(200);
        raw("DELETE", "/receivers/badsecret", "Bearer " + TOKEN, null);
    }
}
