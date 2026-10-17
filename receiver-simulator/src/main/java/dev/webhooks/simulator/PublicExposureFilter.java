package dev.webhooks.simulator;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards a simulator that is reachable from the internet.
 * <ul>
 *   <li><b>Control token.</b> With {@code SIMULATOR_CONTROL_TOKEN} set, everything except receiving
 *       webhooks ({@code POST /receivers/{name}}) and {@code GET /healthz} needs
 *       {@code Authorization: Bearer <token>}: configuring receivers, reading their request logs
 *       (which contain payloads), deleting them and {@code /verify}. Without a token the simulator
 *       refuses to start, unless {@code SIMULATOR_OPEN_CONTROL_API=true} explicitly opens the control
 *       API for local use (Docker Compose sets it). A deployment that forgets the token therefore
 *       fails instead of exposing the control API.</li>
 *   <li><b>Body limit.</b> Request bodies over 1 MB are refused, declared or chunked.</li>
 * </ul>
 */
@Component
public class PublicExposureFilter extends OncePerRequestFilter {

    static final long MAX_BODY_BYTES = 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(PublicExposureFilter.class);

    private final byte[] token;

    public PublicExposureFilter(@Value("${simulator.control-token:}") String token,
            @Value("${simulator.open-control-api:false}") boolean openControlApi) {
        this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
        if (this.token == null) {
            if (!openControlApi) {
                throw new IllegalStateException("SIMULATOR_CONTROL_TOKEN is not set. Set it to protect the control API, "
                        + "or set SIMULATOR_OPEN_CONTROL_API=true to leave it open on a machine nobody else can reach.");
            }
            log.warn("SIMULATOR_OPEN_CONTROL_API=true and no SIMULATOR_CONTROL_TOKEN: the control API is open. "
                    + "Local use only; never expose this simulator publicly.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (token != null && !isPublic(request) && !hasToken(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"control API requires Authorization: Bearer <SIMULATOR_CONTROL_TOKEN>\"}");
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(413);
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    /**
     * Webhook deliveries and the health check are open; everything else is control. A POST to the root
     * or to {@code /receivers} is open too: it only returns the hint that names the right path, so a
     * misconfigured endpoint sees why its delivery failed instead of a token error.
     */
    static boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (HttpMethod.GET.matches(request.getMethod()) && path.equals("/healthz")) {
            return true;
        }
        return HttpMethod.POST.matches(request.getMethod())
                && (path.matches("/receivers/[^/]+") || ReceiverController.WRONG_PATHS.contains(path));
    }

    private boolean hasToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && MessageDigest.isEqual(token, header.substring(7).trim().getBytes(StandardCharsets.UTF_8));
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream delegate = super.getInputStream();
                stream = new ServletInputStream() {
                    private long read;

                    private void count(long n) throws IOException {
                        if (n > 0 && (read += n) > MAX_BODY_BYTES) {
                            throw new BodyTooLargeException();
                        }
                    }

                    @Override
                    public int read() throws IOException {
                        int b = delegate.read();
                        if (b >= 0) {
                            count(1);
                        }
                        return b;
                    }

                    @Override
                    public int read(byte[] buffer, int off, int len) throws IOException {
                        int n = delegate.read(buffer, off, len);
                        count(n);
                        return n;
                    }

                    @Override
                    public boolean isFinished() {
                        return delegate.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        delegate.setReadListener(listener);
                    }
                };
            }
            return stream;
        }
    }

    /** A chunked body went over the limit while being read. */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
    }
}
