package dev.webhooks.platform.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the Prometheus scraper on {@code /actuator/prometheus} with a static bearer token
 * ({@code METRICS_TOKEN}), compared in constant time. Without a configured token nobody is let in.
 */
public class MetricsTokenFilter extends OncePerRequestFilter {

    public static final String PATH = "/actuator/prometheus";
    public static final String ROLE_METRICS = "ROLE_METRICS";

    private final byte[] token;

    public MetricsTokenFilter(String token) {
        this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (token != null && header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && MessageDigest.isEqual(token, header.substring(7).trim().getBytes(StandardCharsets.UTF_8))) {
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "metrics-scraper", null, List.of(new SimpleGrantedAuthority(ROLE_METRICS))));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
