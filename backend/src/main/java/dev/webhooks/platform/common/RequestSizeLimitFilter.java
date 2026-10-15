package dev.webhooks.platform.common;

import dev.webhooks.platform.config.WebhookProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Caps API request bodies (256 KB by default) before any JSON parsing. A declared
 * {@code Content-Length} over the limit is refused immediately; a chunked body is counted while it is
 * read and the request fails as soon as it passes the limit, so an oversized body is never buffered.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ObjectMapper objectMapper;

    public RequestSizeLimitFilter(WebhookProperties properties, ObjectMapper objectMapper) {
        this.maxBytes = properties.limits().maxRequestBytes();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), problem(maxBytes));
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    static ProblemDetail problem(long maxBytes) {
        return GlobalExceptionHandler.problem(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "Request body must be at most " + maxBytes + " bytes");
    }

    /** Thrown while reading a body that exceeds the limit; mapped to 413. */
    public static class PayloadTooLargeException extends IOException {
        private final long maxBytes;

        PayloadTooLargeException(long maxBytes) {
            super("Request body exceeds " + maxBytes + " bytes");
            this.maxBytes = maxBytes;
        }

        public ProblemDetail problem() {
            return RequestSizeLimitFilter.problem(maxBytes);
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }
    }

    private static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        LimitedStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        private void count(long n) throws PayloadTooLargeException {
            if (n > 0) {
                read += n;
                if (read > maxBytes) {
                    throw new PayloadTooLargeException(maxBytes);
                }
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
    }
}
