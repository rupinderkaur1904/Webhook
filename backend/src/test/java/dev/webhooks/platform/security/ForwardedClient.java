package dev.webhooks.platform.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Sends login attempts and plain GETs to the embedded server with chosen forwarding headers. */
final class ForwardedClient {

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final ObjectMapper json;
    private final String base;
    private String csrf;

    ForwardedClient(int port, ObjectMapper json) {
        this.base = "http://127.0.0.1:" + port;
        this.json = json;
    }

    /** One failed login for a fresh email (so only the per-address limit can trigger). Returns the status. */
    int failedLogin(String forwardedFor) throws Exception {
        if (csrf == null) {
            csrf = json.readTree(get("/api/v1/auth/csrf", null, null).body()).get("token").asString();
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"nobody-" + UUID.randomUUID() + "@test.local\",\"password\":\"wrong-password\"}"));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    HttpResponse<String> get(String path, String forwardedFor, String forwardedProto) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        if (forwardedProto != null) {
            request.header("X-Forwarded-Proto", forwardedProto);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
