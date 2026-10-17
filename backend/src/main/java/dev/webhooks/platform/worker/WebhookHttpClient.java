package dev.webhooks.platform.worker;

import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.ssrf.DestinationBlockedException;
import dev.webhooks.platform.ssrf.DestinationPolicy;
import dev.webhooks.platform.ssrf.SsrfDnsResolver;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The only component that sends webhook requests. Hardening:
 * <ul>
 *   <li>DNS resolution goes through {@link SsrfDnsResolver}; connections use only checked addresses.</li>
 *   <li>Redirects, cookies, automatic retries and system proxy settings are all disabled.</li>
 *   <li>Connect and per-read timeouts, plus a hard total deadline that aborts slow-dripping responses.</li>
 *   <li>Only the first {@code storedResponseBodyBytes} of the response body are read; if more follows, the
 *       connection is dropped instead of draining the rest.</li>
 * </ul>
 */
@Component
public class WebhookHttpClient implements DisposableBean {

    private static final Set<String> STORED_RESPONSE_HEADERS = Set.of("content-type", "content-length", "retry-after", "date");

    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines;
    private final Duration totalTimeout;
    private final int storedBodyBytes;

    public WebhookHttpClient(WebhookProperties properties, DestinationPolicy destinationPolicy) {
        var delivery = properties.delivery();
        this.totalTimeout = delivery.responseTimeout();
        this.storedBodyBytes = delivery.storedResponseBodyBytes();
        int maxConnections = Math.max(properties.worker().maxConcurrency() * 2, 4);
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new SsrfDnsResolver(destinationPolicy))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(delivery.connectTimeout()))
                        .setSocketTimeout(Timeout.of(delivery.responseTimeout()))
                        .build())
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnections)
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(false)
                        .setResponseTimeout(Timeout.of(delivery.responseTimeout()))
                        .setConnectionRequestTimeout(Timeout.of(delivery.connectTimeout()))
                        .build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAutomaticRetries()
                .disableAuthCaching()
                .setUserAgent("WebhookPlatform/1.0")
                .build();
        this.deadlines = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("webhook-deadline").factory());
    }

    public HttpAttemptResult post(URI url, Map<String, String> headers, byte[] body) {
        HttpPost request = new HttpPost(url);
        headers.forEach(request::setHeader);
        request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));

        AtomicBoolean deadlineHit = new AtomicBoolean();
        ScheduledFuture<?> deadline = deadlines.schedule(() -> {
            deadlineHit.set(true);
            request.cancel();
        }, totalTimeout.toMillis(), TimeUnit.MILLISECONDS);

        long start = System.nanoTime();
        ClassicHttpResponse response = null;
        boolean bodyComplete = false;
        try {
            response = client.executeOpen(null, request, null);
            int status = response.getCode();
            Map<String, String> storedHeaders = new LinkedHashMap<>();
            for (Header header : response.getHeaders()) {
                String name = header.getName().toLowerCase(Locale.ROOT);
                if (STORED_RESPONSE_HEADERS.contains(name)) {
                    storedHeaders.putIfAbsent(name, truncate(header.getValue(), 200));
                }
            }
            Excerpt excerpt = readExcerpt(response.getEntity());
            bodyComplete = excerpt.complete();
            return new HttpAttemptResult(outcomeFor(status), status, storedHeaders, excerpt.text(), null, elapsed(start));
        } catch (DestinationBlockedException e) {
            return HttpAttemptResult.failure(AttemptOutcome.DESTINATION_BLOCKED,
                    "Destination resolves to a private, loopback, link-local or reserved address", elapsed(start));
        } catch (UnknownHostException e) {
            return HttpAttemptResult.failure(AttemptOutcome.DNS_ERROR, "Destination host could not be resolved", elapsed(start));
        } catch (ConnectTimeoutException e) {
            return HttpAttemptResult.failure(AttemptOutcome.TIMEOUT, "Timed out connecting to the destination", elapsed(start));
        } catch (SocketTimeoutException e) {
            return HttpAttemptResult.failure(AttemptOutcome.TIMEOUT, "Timed out waiting for the response", elapsed(start));
        } catch (SSLException e) {
            return HttpAttemptResult.failure(AttemptOutcome.TLS_ERROR, "TLS handshake or certificate validation failed", elapsed(start));
        } catch (ConnectException e) {
            return HttpAttemptResult.failure(AttemptOutcome.CONNECTION_ERROR, "Connection refused by the destination", elapsed(start));
        } catch (IOException e) {
            if (deadlineHit.get() || e instanceof InterruptedIOException) {
                return HttpAttemptResult.failure(AttemptOutcome.TIMEOUT,
                        "No complete response within " + totalTimeout.toSeconds() + "s", elapsed(start));
            }
            if (e instanceof org.apache.hc.client5.http.ClientProtocolException
                    || e.getCause() instanceof org.apache.hc.core5.http.ProtocolException) {
                return HttpAttemptResult.failure(AttemptOutcome.INVALID_RESPONSE, "The destination sent an invalid HTTP response", elapsed(start));
            }
            return HttpAttemptResult.failure(AttemptOutcome.CONNECTION_ERROR,
                    "Connection failed (" + e.getClass().getSimpleName() + ")", elapsed(start));
        } finally {
            deadline.cancel(false);
            close(response, bodyComplete);
        }
    }

    /**
     * A fully read response returns its connection to the pool. Otherwise the connection is dropped
     * at once: a graceful close would read (drain) the rest of the body, so a receiver that answers
     * 2xx and then sends a large or stalling body would hold the attempt until the deadline and turn
     * an accepted delivery into a timeout.
     */
    private static void close(ClassicHttpResponse response, boolean bodyComplete) {
        if (response == null) {
            return;
        }
        try {
            if (!bodyComplete && response instanceof ModalCloseable modal) {
                modal.close(CloseMode.IMMEDIATE);
            } else {
                response.close();
            }
        } catch (IOException | RuntimeException e) {
            // The outcome is already decided; the connection is discarded either way.
        }
    }

    static AttemptOutcome outcomeFor(int status) {
        if (status >= 200 && status < 300) {
            return AttemptOutcome.SUCCESS;
        }
        if (status >= 300 && status < 400) {
            return AttemptOutcome.HTTP_REDIRECT;
        }
        if (status == 429) {
            return AttemptOutcome.RATE_LIMITED;
        }
        if (status >= 400 && status < 500) {
            return AttemptOutcome.HTTP_CLIENT_ERROR;
        }
        if (status >= 500 && status < 600) {
            return AttemptOutcome.HTTP_SERVER_ERROR;
        }
        return AttemptOutcome.INVALID_RESPONSE;
    }

    private record Excerpt(String text, boolean complete) {}

    /**
     * Reads at most {@code storedBodyBytes} of the body. The stream is not closed here: closing it
     * would drain the remainder ({@link #close}).
     */
    private Excerpt readExcerpt(HttpEntity entity) throws IOException {
        if (entity == null) {
            return new Excerpt(null, true);
        }
        InputStream in = entity.getContent();
        if (in == null) {
            return new Excerpt(null, true);
        }
        byte[] bytes = in.readNBytes(storedBodyBytes);
        return new Excerpt(sanitize(new String(bytes, StandardCharsets.UTF_8)), bytes.length < storedBodyBytes);
    }

    /** Removes control characters (except newline and tab) so stored text is safe to display. */
    static String sanitize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t' || (!Character.isISOControl(cp) && cp != 0xFFFD)) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static long elapsed(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    @Override
    public void destroy() throws IOException {
        deadlines.shutdownNow();
        client.close();
    }
}
