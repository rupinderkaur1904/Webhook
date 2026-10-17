import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal Standard Webhooks receiver using only the JDK.
 *
 * <pre>WEBHOOK_SECRET=whsec_... PORT=3000 java Receiver.java</pre>
 */
public final class Receiver {

    public static final long TOLERANCE_SECONDS = 5 * 60;
    static final int MAX_BODY_BYTES = 1024 * 1024;

    /** Outcome of {@link #verify}; {@code reason} is "ok" when valid. */
    public record Result(boolean valid, String reason) {
        static Result ok() { return new Result(true, "ok"); }
        static Result fail(String reason) { return new Result(false, reason); }
    }

    /** A verified, first-seen delivery. */
    public record Event(String webhookId, String type, byte[] rawBody) {}

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,15}");
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]+={0,2}");

    /**
     * Verifies a delivery. {@code headers} is looked up case-insensitively; {@code rawBody} must be the
     * exact bytes received, never JSON that was parsed and written again.
     */
    public static Result verify(String secret, Map<String, String> headers, byte[] rawBody, Instant now) {
        String id = header(headers, "webhook-id");
        String timestamp = header(headers, "webhook-timestamp");
        String signatures = header(headers, "webhook-signature");
        if (id == null || timestamp == null || signatures == null) {
            return Result.fail("missing_header");
        }
        if (!DIGITS.matcher(timestamp).matches()) {
            return Result.fail("invalid_timestamp");
        }
        // Reject in both directions: an old timestamp is a replay, a future one a skewed or forged clock.
        if (Math.abs(now.getEpochSecond() - Long.parseLong(timestamp)) > TOLERANCE_SECONDS) {
            return Result.fail("timestamp_out_of_tolerance");
        }
        byte[] expected = hmac(secret, (id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8), rawBody);
        // Several signatures are sent during a secret rotation; any one matching is enough.
        for (String entry : signatures.trim().split(" +")) {
            if (!entry.startsWith("v1,")) {
                continue; // unknown version: ignore, a future scheme must not break today's receivers
            }
            String encoded = entry.substring(3);
            if (encoded.length() % 4 != 0 || !BASE64.matcher(encoded).matches()) {
                continue; // malformed entry: skip it, another entry may still match
            }
            if (MessageDigest.isEqual(expected, Base64.getDecoder().decode(encoded))) { // constant time
                return Result.ok();
            }
        }
        return Result.fail("no_matching_signature");
    }

    private static byte[] hmac(String secret, byte[] prefix, byte[] body) {
        String encoded = secret.startsWith("whsec_") ? secret.substring("whsec_".length()) : secret;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(encoded), "HmacSHA256"));
            mac.update(prefix);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Starts the receiver on {@code port} (0 picks a free one). {@code onEvent} runs once per webhook-id.
     */
    public static HttpServer start(int port, String secret, Consumer<Event> onEvent) throws IOException {
        // Deliveries are at-least-once: the same webhook-id arrives again after a timeout, a retry or a
        // replay. This in-memory set forgets everything on restart and is not shared between instances;
        // production needs a durable store (e.g. a unique key in your database, written in the same
        // transaction as the side effect, or Redis SET NX with an expiry longer than the retry window).
        Set<String> seen = ConcurrentHashMap.newKeySet();
        hmac(secret, new byte[0], new byte[0]); // fail at startup, not per request, if the secret is not base64

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/webhooks", exchange -> {
            try (exchange) {
                if (!exchange.getRequestURI().getPath().equals("/webhooks")) {
                    respond(exchange, 404, "not found");
                    return;
                }
                if (!exchange.getRequestMethod().equals("POST")) {
                    respond(exchange, 405, "method not allowed");
                    return;
                }
                byte[] body = readBody(exchange.getRequestBody());
                if (body == null) {
                    respond(exchange, 413, "payload too large");
                    return;
                }
                Map<String, String> headers = new java.util.HashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.isEmpty() ? null : v.get(0)));

                Result result = verify(secret, headers, body, Instant.now());
                if (!result.valid()) {
                    log("rejected delivery: " + result.reason());
                    respond(exchange, 401, "invalid signature");
                    return;
                }
                String id = header(headers, "webhook-id");
                if (!seen.add(id)) {
                    log("duplicate " + id + ", already processed");
                    respond(exchange, 200, "duplicate");
                    return;
                }
                Event event = new Event(id, eventType(body), body);
                try {
                    // Keep this fast: the platform times out slow receivers and retries. Real work belongs
                    // on a queue; acknowledge once the event is safely recorded.
                    log("received " + event.type() + " " + id);
                    onEvent.accept(event);
                } catch (RuntimeException e) {
                    seen.remove(id); // not handled, so let the platform's retry through
                    respond(exchange, 500, "error");
                    return;
                }
                respond(exchange, 200, "ok");
            } catch (IOException e) {
                log("request failed: " + e);
            }
        });
        server.start();
        return server;
    }

    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([^\"\\\\]*)\"");

    /**
     * The JDK has no JSON parser, so this pulls the top-level "type" (the envelope writes it before "data").
     * A real receiver parses the verified body with its JSON library.
     */
    static String eventType(byte[] body) {
        Matcher m = TYPE.matcher(new String(body, StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : "unknown";
    }

    private static byte[] readBody(InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
        return body.length > MAX_BODY_BYTES ? null : body;
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void log(String message) {
        System.out.println(Instant.now() + " " + message);
    }

    public static void main(String[] args) throws IOException {
        String secret = System.getenv("WEBHOOK_SECRET");
        if (secret == null || secret.isBlank()) {
            System.err.println("Set WEBHOOK_SECRET to the endpoint's signing secret (whsec_...).");
            System.exit(1);
        }
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000"));
        start(port, secret, event -> {});
        log("listening on http://localhost:" + port + "/webhooks");
    }
}
