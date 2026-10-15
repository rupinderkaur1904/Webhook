import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Checks Receiver against the shared vectors (signed by the platform's own signer) and over HTTP.
 *
 * <pre>javac -d build Receiver.java && java -cp build VerifyTest.java</pre>
 *
 * Exits non-zero if any check fails.
 */
public class VerifyTest {

    static int passed;
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path vectorsFile = Path.of(args.length > 0 ? args[0] : "../test-vectors.json");
        Map<String, Object> root = asMap(new Json(Files.readString(vectorsFile, StandardCharsets.UTF_8)).parse());
        List<Object> vectors = asList(root.get("vectors"));

        for (Object o : vectors) {
            Map<String, Object> v = asMap(o);
            Map<String, String> headers = new LinkedHashMap<>();
            asMap(v.get("headers")).forEach((k, val) -> headers.put(k, (String) val));
            Map<String, Object> expected = asMap(v.get("expected"));
            Receiver.Result r = Receiver.verify((String) v.get("secret"), headers,
                    ((String) v.get("body")).getBytes(StandardCharsets.UTF_8),
                    Instant.ofEpochSecond(((Number) v.get("now")).longValue()));
            check("vector " + v.get("name"),
                    r.valid() == (Boolean) expected.get("valid") && r.reason().equals(expected.get("reason")),
                    "expected " + expected + ", got " + r);
        }

        serverTest(asMap(vectors.get(0)));

        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /** Valid request -> 2xx, tampered -> 401, duplicate webhook-id -> 2xx but processed once. */
    static void serverTest(Map<String, Object> valid) throws Exception {
        String secret = (String) valid.get("secret");
        String body = (String) valid.get("body");
        Map<String, Object> vh = asMap(valid.get("headers"));
        // The vectors use a fixed 'now'; re-sign at the current time with the platform format so the
        // timestamp check passes.
        String id = (String) vh.get("webhook-id");
        long ts = Instant.now().getEpochSecond();
        String signature = "v1," + java.util.Base64.getEncoder().encodeToString(hmac(secret, id + "." + ts + "." + body));

        List<Receiver.Event> processed = new CopyOnWriteArrayList<>();
        HttpServer server = Receiver.start(0, secret, processed::add);
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/webhooks");
            HttpClient client = HttpClient.newHttpClient();

            int first = post(client, uri, id, ts, signature, body);
            check("server: valid request is acknowledged", first >= 200 && first < 300, "status " + first);

            int tampered = post(client, uri, "tampered-" + id, ts, signature, body.replace("42.5", "42.9"));
            check("server: tampered body is rejected with 401", tampered == 401, "status " + tampered);

            int dup = post(client, uri, id, ts, signature, body);
            check("server: duplicate webhook-id is acknowledged", dup >= 200 && dup < 300, "status " + dup);
            check("server: duplicate webhook-id is processed once",
                    processed.size() == 1 && processed.get(0).type().equals("order.created"),
                    "processed " + processed);
        } finally {
            server.stop(0);
        }
    }

    static int post(HttpClient client, URI uri, String id, long ts, String signature, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("webhook-id", id)
                .header("webhook-timestamp", Long.toString(ts))
                .header("webhook-signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    static byte[] hmac(String secret, String content) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                java.util.Base64.getDecoder().decode(secret.substring("whsec_".length())), "HmacSHA256"));
        return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("ok   " + name);
        } else {
            failures.add(name);
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) { return (List<Object>) o; }

    /** Just enough JSON to read the vectors file (the JDK has no JSON parser). */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) { this.s = s; }

        Object parse() {
            Object v = value();
            ws();
            if (i != s.length()) throw error("trailing data");
            return v;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw error("unexpected '" + c + "'");
            String num = s.substring(start, i);
            return num.matches("-?\\d+") ? (Object) Long.parseLong(num) : (Object) Double.parseDouble(num);
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                m.put(key, value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> throw error("bad escape");
                }
            }
        }

        private void expect(char c) {
            if (s.charAt(i) != c) throw error("expected '" + c + "'");
            i++;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + i);
        }
    }
}
