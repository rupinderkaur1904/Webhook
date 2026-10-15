package dev.webhooks.platform.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** A real HTTP server on 127.0.0.1 with scriptable responses, for delivery tests. */
public final class TestReceiver implements AutoCloseable {

    public record Request(String path, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public record Response(int status, Map<String, String> headers, String body, long delayMs) {
        public static Response status(int status) {
            return new Response(status, Map.of(), "{\"received\":true}", 0);
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, Response> handler = r -> Response.status(200);

    public TestReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            Request request = new Request(exchange.getRequestURI().getPath(), Map.copyOf(exchange.getRequestHeaders()),
                    exchange.getRequestBody().readAllBytes());
            requests.add(request);
            Response response = handler.apply(request);
            try {
                if (response.delayMs() > 0) {
                    Thread.sleep(response.delayMs());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            } catch (IOException e) {
                // client went away (e.g. timed out); nothing to do
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    public void respondWith(Function<Request, Response> handler) {
        this.handler = handler;
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public List<Request> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
