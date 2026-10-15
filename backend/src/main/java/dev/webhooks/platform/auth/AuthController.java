package dev.webhooks.platform.auth;

import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard login. Sessions live in PostgreSQL (Spring Session JDBC); the browser holds only an
 * HttpOnly cookie. Every state-changing call, login included, needs the CSRF token from
 * {@code GET /auth/csrf}.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 128) String password) {}

    public record UserResponse(UUID id, String email) {}

    public record CsrfResponse(String headerName, String token) {}

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository;
    private final CookieCsrfTokenRepository csrfTokenRepository;
    private final CsrfAuthenticationStrategy csrfRotation;
    private final RateLimiter rateLimiter;
    private final boolean registrationEnabled;
    /** Compared against when the email is unknown, so response time does not reveal which accounts exist. */
    private final String dummyHash;

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder,
            SecurityContextRepository securityContextRepository, CookieCsrfTokenRepository csrfTokenRepository,
            RateLimiter rateLimiter, WebhookProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.csrfRotation = new CsrfAuthenticationStrategy(csrfTokenRepository);
        var plainHandler = new CsrfTokenRequestAttributeHandler();
        plainHandler.setCsrfRequestAttributeName(null);
        this.csrfRotation.setRequestHandler(plainHandler);
        this.rateLimiter = rateLimiter;
        this.registrationEnabled = properties.auth().registrationEnabled();
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** Issues the CSRF token (also set as the XSRF-TOKEN cookie). */
    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    UserResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        rateLimiter.checkLogin("ip:" + request.getRemoteAddr());
        rateLimiter.checkLogin("email:" + email);

        var user = users.findByEmail(email);
        String hash = user.map(UserRepository.User::passwordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(body.password(), hash);
        if (user.isEmpty() || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect");
        }
        UUID userId = user.get().id();
        users.recordLogin(userId, passwordEncoder.upgradeEncoding(hash) ? passwordEncoder.encode(body.password()) : null);

        // Session fixation: never reuse a session that existed before authentication.
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        request.getSession(true);

        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                AccountPrincipal.forSession(userId), null, List.of(new SimpleGrantedAuthority(AccountPrincipal.ROLE_USER)));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        // A fresh CSRF token for the new session; loading it writes the new XSRF-TOKEN cookie.
        csrfRotation.onAuthentication(authentication, request, response);
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken rotated) {
            rotated.getToken();
        }
        return new UserResponse(userId, user.get().email());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrfTokenRepository.saveToken(null, request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    UserResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        var user = users.find(principal.accountId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Account no longer exists"));
        return new UserResponse(user.id(), user.email());
    }

    /** Self-registration; off by default (webhooks.auth.registration-enabled), in which case the route does not exist. */
    @PostMapping("/register")
    ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        if (!registrationEnabled) {
            throw ApiException.notFound("Resource");
        }
        rateLimiter.checkLogin("ip:" + request.getRemoteAddr());
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        UUID id = users.insert(email, passwordEncoder.encode(body.password()))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "An account with this email already exists"));
        return ResponseEntity.status(HttpStatus.CREATED).body(new UserResponse(id, email));
    }
}
