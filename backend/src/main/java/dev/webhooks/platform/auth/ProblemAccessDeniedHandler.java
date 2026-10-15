package dev.webhooks.platform.auth;

import dev.webhooks.platform.common.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.ObjectMapper;

/** 403 as problem+json: a missing or stale CSRF token, or an API key used on a dashboard-only route. */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ProblemAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        var body = ex instanceof CsrfException
                ? GlobalExceptionHandler.problem(HttpStatus.FORBIDDEN, "CSRF_TOKEN_INVALID",
                        "Missing or invalid CSRF token; send the XSRF-TOKEN cookie value in the X-XSRF-TOKEN header")
                : GlobalExceptionHandler.problem(HttpStatus.FORBIDDEN, "SESSION_REQUIRED",
                        "This operation requires a dashboard session; API keys can only submit events and read");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
