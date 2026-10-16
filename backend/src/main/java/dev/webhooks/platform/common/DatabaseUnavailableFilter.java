package dev.webhooks.platform.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers {@code 503 DATABASE_UNAVAILABLE} (problem+json, {@code Retry-After}) when PostgreSQL cannot be
 * reached. Session loading and API-key lookup run in filters, outside the controllers' exception
 * handling; without this, a database outage surfaced as {@code 401 UNAUTHENTICATED} ("send a valid API
 * key"), telling producers their valid key was wrong. Runs just inside {@link RequestIdFilter}, so the
 * problem carries the request id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DatabaseUnavailableFilter extends OncePerRequestFilter {

    static final String RETRY_AFTER_SECONDS = "5";
    private static final Logger log = LoggerFactory.getLogger(DatabaseUnavailableFilter.class);

    private final ObjectMapper objectMapper;

    public DatabaseUnavailableFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException e) {
            if (!isDatabaseUnavailable(e) || response.isCommitted()) {
                throw e;
            }
            log.warn("Database unavailable: {}", e.toString());
            response.resetBuffer();
            write(response);
        }
    }

    public static boolean isDatabaseUnavailable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException || cause instanceof TransientDataAccessResourceException) {
                return true;
            }
        }
        return false;
    }

    private void write(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.problem(HttpStatus.SERVICE_UNAVAILABLE,
                "DATABASE_UNAVAILABLE", "The database is temporarily unavailable; retry shortly"));
    }
}
