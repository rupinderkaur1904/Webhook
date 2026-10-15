package dev.webhooks.platform.auth;

import dev.webhooks.platform.crypto.Tokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer whk_...} API keys. Keys are looked up by their SHA-256
 * hash; the plaintext is never stored. A malformed, unknown or revoked key fails the request with
 * 401 rather than falling through as anonymous.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyRepository apiKeys;
    private final AuthenticationEntryPoint entryPoint;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeys, AuthenticationEntryPoint entryPoint) {
        this.apiKeys = apiKeys;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            chain.doFilter(request, response);
            return;
        }
        String key = header.substring(7).trim();
        var principal = key.startsWith(Tokens.API_KEY_PREFIX)
                ? apiKeys.findActiveByHash(Tokens.sha256(key))
                : java.util.Optional.<AccountPrincipal>empty();
        if (principal.isEmpty()) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("Invalid API key"));
            return;
        }
        AccountPrincipal account = principal.get();
        apiKeys.touch(UUID.fromString(account.credentialId().substring("api-key:".length())));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                account, null, List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
