package dev.webhooks.platform.config;

import static dev.webhooks.platform.auth.AccountPrincipal.ROLE_API_CLIENT;
import static dev.webhooks.platform.auth.AccountPrincipal.ROLE_USER;

import dev.webhooks.platform.auth.ApiKeyAuthenticationFilter;
import dev.webhooks.platform.auth.ApiKeyRepository;
import dev.webhooks.platform.auth.CsrfCookieFilter;
import dev.webhooks.platform.auth.MetricsTokenFilter;
import dev.webhooks.platform.auth.ProblemAccessDeniedHandler;
import dev.webhooks.platform.auth.ProblemAuthenticationEntryPoint;
import dev.webhooks.platform.web.DashboardController;
import jakarta.servlet.DispatcherType;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import dev.webhooks.platform.common.GlobalExceptionHandler;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import tools.jackson.databind.ObjectMapper;

/**
 * Two ways in, both resolving to an {@code AccountPrincipal}:
 * <ul>
 *   <li><b>Dashboard session</b> ({@code ROLE_USER}): email + password login, server-side session in
 *       PostgreSQL, HttpOnly cookie, CSRF token required on every state-changing request.</li>
 *   <li><b>API key</b> ({@code ROLE_API_CLIENT}): {@code Authorization: Bearer whk_...}, for producers.
 *       Keys may submit events and read; they cannot manage endpoints, keys, or replays, so a leaked
 *       ingestion key cannot redirect webhooks or mint more keys.</li>
 * </ul>
 * Requests carrying a Bearer token are exempt from CSRF: browsers never attach that header on their
 * own, and the API-key filter replaces any session authentication on such a request.
 */
@Configuration
public class SecurityConfig {

    public static final String SESSION_COOKIE = "WEBHOOKS_SESSION";

    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; "
            + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiKeyRepository apiKeys, ObjectMapper objectMapper,
            CookieCsrfTokenRepository csrfTokenRepository, SecurityContextRepository securityContextRepository,
            WebhookProperties properties) throws Exception {
        var entryPoint = new ProblemAuthenticationEntryPoint(objectMapper);
        // Plain (non-XOR) handler: the SPA echoes the cookie value in X-XSRF-TOKEN. BREACH masking only
        // matters for tokens rendered into compressed HTML, which this API never does.
        var csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers(ApiKeyAuthenticationFilter::hasBearerToken))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .securityContext(c -> c.securityContextRepository(securityContextRepository))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.NEVER))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable()) // POST /api/v1/auth/logout is handled by AuthController
                .requestCache(c -> c.disable())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new ProblemAccessDeniedHandler(objectMapper)))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeys, entryPoint), AnonymousAuthenticationFilter.class)
                .addFilterBefore(new MetricsTokenFilter(properties.metrics().token()), ApiKeyAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // The container's own error dispatch (e.g. Tomcat refusing TRACE with 405) keeps its
                        // status instead of becoming a 401; a direct request to /error is still denied.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, MetricsTokenFilter.PATH).hasAuthority(MetricsTokenFilter.ROLE_METRICS)
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                        .requestMatchers("/api/v1/auth/**", "/api/v1/api-keys", "/api/v1/api-keys/**")
                                .hasAuthority(ROLE_USER)
                        .requestMatchers(HttpMethod.POST, "/api/v1/events").hasAnyAuthority(ROLE_USER, ROLE_API_CLIENT)
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyAuthority(ROLE_USER, ROLE_API_CLIENT)
                        .requestMatchers("/api/**").hasAuthority(ROLE_USER)
                        // The dashboard's static files and client-side routes; its data needs a session.
                        .requestMatchers(HttpMethod.GET, DashboardController.ROUTES).permitAll()
                        .requestMatchers(HttpMethod.GET, "/index.html", "/assets/**", "/favicon.svg").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    /**
     * Requests the firewall rejects (encoded dots, {@code //}, {@code ;} path parameters, TRACE, ...)
     * get a 400 problem. Spring Security's default rethrows, and the container's error dispatch then
     * ends in this chain's entry point: a misleading 401 without the request id or hardening headers.
     */
    @Bean
    RequestRejectedHandler requestRejectedHandler(ObjectMapper objectMapper) {
        return (request, response, ex) -> {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Cache-Control", "no-store");
            objectMapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST,
                    "REQUEST_REJECTED", "The request method or URL is not allowed"));
        };
    }

    /** Readable by the SPA (it must echo the value in a header); scoped to the whole site. */
    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(c -> c.path("/").sameSite("Lax"));
        return repository;
    }

    /**
     * The session cookie, configured explicitly rather than through {@code server.servlet.session.cookie.*}
     * (Spring Boot applies those to Spring Session only with an embedded server, so tests would not
     * exercise them). {@code SESSION_COOKIE_SECURE=false} is for plain-HTTP local development only.
     */
    @Bean
    CookieSerializer sessionCookieSerializer(WebhookProperties properties) {
        var serializer = new DefaultCookieSerializer();
        serializer.setCookieName(SESSION_COOKIE);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(properties.auth().sessionCookieSecure());
        serializer.setSameSite("Lax");
        return serializer;
    }

    /** Sessions only: API-key requests authenticate afresh each time and must never create a session. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        // SessionCreationPolicy.NEVER is not applied to a repository supplied as a bean, so it is set here:
        // only AuthController's login creates a session. API-key authentications are never stored, or the
        // framework's session management would save them and every API call would get a session row and a
        // cookie that keeps working after the key is revoked.
        var sessions = new HttpSessionSecurityContextRepository();
        sessions.setAllowSessionCreation(false);
        return new SecurityContextRepository() {
            @Override
            @SuppressWarnings("deprecation")
            public org.springframework.security.core.context.SecurityContext loadContext(
                    org.springframework.security.web.context.HttpRequestResponseHolder holder) {
                return sessions.loadContext(holder);
            }

            @Override
            public org.springframework.security.core.context.DeferredSecurityContext loadDeferredContext(
                    jakarta.servlet.http.HttpServletRequest request) {
                return sessions.loadDeferredContext(request);
            }

            @Override
            public void saveContext(org.springframework.security.core.context.SecurityContext context,
                    jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
                var authentication = context.getAuthentication();
                if (authentication != null && authentication.getAuthorities().stream()
                        .anyMatch(a -> ROLE_API_CLIENT.equals(a.getAuthority()))) {
                    return;
                }
                sessions.saveContext(context, request, response);
            }

            @Override
            public boolean containsContext(jakarta.servlet.http.HttpServletRequest request) {
                return sessions.containsContext(request);
            }
        };
    }

    /**
     * Argon2id for new hashes. Existing bcrypt hashes (from before Stage 5) still verify and are
     * re-hashed with Argon2id on the user's next successful login.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        String defaultId = "argon2";
        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder(defaultId, Map.of(
                defaultId, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                "bcrypt", new BCryptPasswordEncoder()));
        return encoder;
    }
}
