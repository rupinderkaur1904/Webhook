package dev.webhooks.platform.support;

import dev.webhooks.platform.WebhookPlatformApplication;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The platform as a separate JVM, configured the way an operator configures it (environment variables
 * and command-line properties), with its console output captured to a file. Used where the behaviour
 * under test is process-wide: startup failures, the console log format, real HTTP and shutdown.
 */
public final class PlatformProcess implements AutoCloseable {

    private final Process process;
    private final Path output;
    private final int port;

    private PlatformProcess(Process process, Path output, int port) {
        this.process = process;
        this.output = output;
        this.port = port;
    }

    /** Starts the platform with only {@code env} and {@code args} (no profile unless given). */
    public static PlatformProcess start(List<String> jvmArgs, Map<String, String> env, List<String> args)
            throws IOException {
        int port = freePort();
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>(List.of(java));
        command.addAll(jvmArgs);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), WebhookPlatformApplication.class.getName(),
                "--server.port=" + port));
        command.addAll(args);
        Path output = Files.createTempFile("platform-process", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile())
                .directory(new File("."));
        // Nothing from the parent's configuration leaks in: only what the caller passes.
        builder.environment().keySet().removeIf(k -> k.startsWith("SPRING_") || k.startsWith("WEBHOOKS_")
                || k.startsWith("DATABASE_") || k.equals("LOG_FORMAT") || k.equals("METRICS_TOKEN"));
        builder.environment().putAll(env);
        return new PlatformProcess(builder.start(), output, port);
    }

    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public Process process() {
        return process;
    }

    public String output() throws IOException {
        return Files.readString(output, StandardCharsets.UTF_8);
    }

    /** Waits until {@code /actuator/health} answers 200, or fails if the process exits first. */
    public void awaitHealthy(Duration timeout) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("platform exited with " + process.exitValue() + ":\n" + output());
            }
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/health"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not listening yet
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("platform did not become healthy:\n" + output());
    }

    /** Waits for the process to exit by itself; returns its exit code, or null if it is still running. */
    public Integer awaitExit(Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) ? process.exitValue() : null;
    }

    /** SIGTERM, then waits for the graceful shutdown to finish. */
    public int stop(Duration timeout) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor();
        }
        return process.exitValue();
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
