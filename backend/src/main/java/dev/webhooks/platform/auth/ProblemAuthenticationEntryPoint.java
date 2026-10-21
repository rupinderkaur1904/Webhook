package dev.webhooks.platform.auth;

import dev.webhooks.platform.common.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/** Returns 401 as problem+json, consistent with every other API error. */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ProblemAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        var body = GlobalExceptionHandler.problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Sign in, or send a valid API key (Authorization: Bearer whk_...)");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (ApiKeyAuthenticationFilter.hasBearerToken(request)) {
            response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        }
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
