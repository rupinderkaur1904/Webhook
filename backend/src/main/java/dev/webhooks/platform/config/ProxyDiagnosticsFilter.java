package dev.webhooks.platform.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Off by default. On a first deployment ({@code PROXY_DIAGNOSTICS=true}, with no trusted proxies yet)
 * it logs the TCP peer and the forwarding headers of each request, so the hosting proxy's real
 * source range can be observed before {@code TRUSTED_PROXIES} is set (docs/DEPLOYMENT.md). Header
 * values are cut to 200 characters; nothing else from the request is logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnBooleanProperty("webhooks.proxy.diagnostics")
public class ProxyDiagnosticsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProxyDiagnosticsFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        log.info("proxy diagnostics: peer={} x-forwarded-for={} x-forwarded-proto={} fly-client-ip={} path={}",
                request.getRemoteAddr(), header(request, "X-Forwarded-For"), header(request, "X-Forwarded-Proto"),
                header(request, "Fly-Client-IP"), request.getRequestURI());
        chain.doFilter(request, response);
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null) {
            return "-";
        }
        String cleaned = value.replaceAll("[^\\x20-\\x7e]", "?");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}
