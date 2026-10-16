package dev.webhooks.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.auth.AccountPrincipal;
import dev.webhooks.platform.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Real login flow through the full filter chain: CSRF bootstrap, Argon2id password check, a session
 * stored in PostgreSQL, session-id rotation on login, and logout as real revocation.
 *
 * <p>Runs in its own application context (registration enabled) and never uses Spring Security's
 * {@code csrf()} test helper, which swaps the CSRF repository of the shared context for a test double.
 * Here every token comes from the real cookie.
 */
@IntegrationTest
@TestPropertySource(properties = "webhooks.auth.registration-enabled=true")
class SessionAuthTest {

    static final String SESSION_COOKIE = "WEBHOOKS_SESSION";
    static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper json;

    String email;
    UUID userId;

    @BeforeEach
    void setUp() {
        email = "login-" + UUID.randomUUID() + "@test.local";
        userId = jdbc.sql("INSERT INTO users (email, password_hash) VALUES (:e, :h) RETURNING id")
                .param("e", email).param("h", passwordEncoder.encode(PASSWORD)).query(UUID.class).single();
    }

    /** What a browser holds: the CSRF cookie (echoed in a header) and, once logged in, the session cookie. */
    record Browser(Cookie xsrf, Cookie session) {
        Cookie[] cookies() {
            return session == null ? new Cookie[] {xsrf} : new Cookie[] {xsrf, session};
        }
    }

    Browser freshBrowser() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie xsrf = lastCookie(result, "XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.isHttpOnly()).as("the SPA must be able to read it").isFalse();
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("token").asString()).isEqualTo(xsrf.getValue());
        return new Browser(xsrf, null);
    }

    /** Like a browser: when a response sets the same cookie twice (clear, then set), the last one wins. */
    static Cookie lastCookie(MvcResult result, String name) {
        Cookie found = null;
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (cookie.getName().equals(name)) {
                found = cookie;
            }
        }
        return found;
    }

    MvcResult login(Browser browser, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").cookie(browser.cookies())
                        .header("X-XSRF-TOKEN", browser.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    Browser loggedIn(Browser before) throws Exception {
        MvcResult result = login(before, PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Cookie session = result.getResponse().getCookie(SESSION_COOKIE);
        Cookie xsrf = lastCookie(result, "XSRF-TOKEN");
        assertThat(session).isNotNull();
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.getSecure()).isTrue();
        assertThat(xsrf).as("CSRF token is rotated on login").isNotNull();
        assertThat(xsrf.getValue()).isNotBlank().isNotEqualTo(before.xsrf().getValue());
        return new Browser(xsrf, session);
    }

    @Test
    void loginCreatesAPersistedSessionThatAuthenticatesDashboardCalls() throws Exception {
        Browser browser = loggedIn(freshBrowser());

        mvc.perform(get("/api/v1/auth/me").cookie(browser.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value(email));
        assertThat(jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = :p")
                .param("p", userId.toString()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_login_at IS NOT NULL FROM users WHERE id = :id").param("id", userId)
                .query(Boolean.class).single()).isTrue();

        // A management call with the session and the (rotated) CSRF token.
        mvc.perform(post("/api/v1/api-keys").cookie(browser.cookies())
                        .header("X-XSRF-TOKEN", browser.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ci\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void stateChangingCallsWithoutTheCsrfTokenAreRejected() throws Exception {
        Browser browser = loggedIn(freshBrowser());

        mvc.perform(post("/api/v1/api-keys").cookie(browser.cookies())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ci\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        mvc.perform(post("/api/v1/api-keys").cookie(browser.cookies()).header("X-XSRF-TOKEN", "forged")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ci\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        // Login itself needs the token (no login CSRF).
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
        MvcResult wrong = login(freshBrowser(), "not the password");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getCookie(SESSION_COOKIE)).isNull();

        String original = email;
        email = "nobody-" + UUID.randomUUID() + "@test.local";
        MvcResult unknown = login(freshBrowser(), PASSWORD);
        email = original;
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(json.readTree(unknown.getResponse().getContentAsString()).get("code").asString())
                .isEqualTo(json.readTree(wrong.getResponse().getContentAsString()).get("code").asString())
                .isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void loggingInAgainIssuesANewSessionIdAndKillsTheOldOne() throws Exception {
        Browser first = loggedIn(freshBrowser());
        Browser second = loggedIn(first);

        assertThat(second.session().getValue()).isNotEqualTo(first.session().getValue());
        mvc.perform(get("/api/v1/auth/me").cookie(first.session())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(second.session())).andExpect(status().isOk());
    }

    @Test
    void logoutRevokesTheSessionServerSide() throws Exception {
        Browser browser = loggedIn(freshBrowser());

        mvc.perform(post("/api/v1/auth/logout").cookie(browser.cookies()).header("X-XSRF-TOKEN", browser.xsrf().getValue()))
                .andExpect(status().isNoContent());

        // Replaying the old cookie (e.g. a stolen copy) no longer works.
        mvc.perform(get("/api/v1/auth/me").cookie(browser.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = :p")
                .param("p", userId.toString()).query(Integer.class).single()).isZero();
    }

    @Test
    void legacyBcryptHashIsUpgradedToArgon2idOnLogin() throws Exception {
        jdbc.sql("UPDATE users SET password_hash = :h WHERE id = :id")
                .param("h", "{bcrypt}" + new BCryptPasswordEncoder().encode(PASSWORD)).param("id", userId).update();

        loggedIn(freshBrowser());

        String upgraded = jdbc.sql("SELECT password_hash FROM users WHERE id = :id").param("id", userId)
                .query(String.class).single();
        assertThat(upgraded).startsWith("{argon2}$argon2id$");
        loggedIn(freshBrowser()); // still logs in with the new hash
    }

    @Test
    void newHashesAreArgon2id() {
        assertThat(passwordEncoder.encode("whatever-password")).startsWith("{argon2}$argon2id$");
    }

    @Test
    void registrationCreatesAnAccountThatCanLogIn() throws Exception {
        Browser browser = freshBrowser();
        String newEmail = "Signup-" + UUID.randomUUID() + "@Test.local";
        mvc.perform(post("/api/v1/auth/register").cookie(browser.cookies()).header("X-XSRF-TOKEN", browser.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + newEmail + "\",\"password\":\"long-enough-password\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(newEmail.toLowerCase()));
        // Same address in another case is the same account (citext).
        mvc.perform(post("/api/v1/auth/register").cookie(browser.cookies()).header("X-XSRF-TOKEN", browser.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + newEmail.toUpperCase() + "\",\"password\":\"long-enough-password\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        mvc.perform(post("/api/v1/auth/register").cookie(browser.cookies()).header("X-XSRF-TOKEN", browser.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"short@test.local\",\"password\":\"too-short\"}"))
                .andExpect(status().isUnprocessableContent());

        email = newEmail;
        assertThat(login(freshBrowser(), "long-enough-password").getResponse().getStatus()).isEqualTo(200);
    }

    /** Audit: cookie attributes, idle timeout in the stored session, and logout needing CSRF. */
    @Test
    void sessionCookieAttributesIdleTimeoutAndLogoutCsrf() throws Exception {
        MvcResult result = login(freshBrowser(), PASSWORD);
        String setCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(SESSION_COOKIE + "=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Path=/")
                .doesNotContain("Domain=");
        assertThat(jdbc.sql("SELECT max_inactive_interval FROM spring_session WHERE principal_name = :p")
                .param("p", userId.toString()).query(Integer.class).single()).isEqualTo(8 * 3600);

        Browser browser = new Browser(lastCookie(result, "XSRF-TOKEN"), result.getResponse().getCookie(SESSION_COOKIE));
        // Logout without the token is refused, so a cross-site form cannot sign the user out.
        mvc.perform(post("/api/v1/auth/logout").cookie(browser.cookies()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        mvc.perform(get("/api/v1/auth/me").cookie(browser.session())).andExpect(status().isOk());
        // An expired session (idle past the timeout) no longer authenticates.
        jdbc.sql("UPDATE spring_session SET last_access_time = last_access_time - 8 * 3600 * 1000 - 1000, "
                        + "expiry_time = expiry_time - 8 * 3600 * 1000 - 1000 WHERE principal_name = :p")
                .param("p", userId.toString()).update();
        mvc.perform(get("/api/v1/auth/me").cookie(browser.session())).andExpect(status().isUnauthorized());
    }

    /** Audit: apart from the request id, a wrong password and an unknown email produce identical answers. */
    @Test
    void wrongPasswordAndUnknownEmailBodiesAreIdentical() throws Exception {
        MvcResult wrong = login(freshBrowser(), "not the password");
        String original = email;
        email = "nobody-" + UUID.randomUUID() + "@test.local";
        MvcResult unknown = login(freshBrowser(), PASSWORD);
        email = original;
        var a = (tools.jackson.databind.node.ObjectNode) json.readTree(wrong.getResponse().getContentAsString());
        var b = (tools.jackson.databind.node.ObjectNode) json.readTree(unknown.getResponse().getContentAsString());
        a.remove("requestId");
        b.remove("requestId");
        assertThat(a).isEqualTo(b);
        assertThat(wrong.getResponse().getHeaderNames()).containsExactlyInAnyOrderElementsOf(unknown.getResponse().getHeaderNames());
    }

    @Test
    void responsesCarryBrowserHardeningHeaders() throws Exception {
        var auth = UsernamePasswordAuthenticationToken.authenticated(AccountPrincipal.forSession(userId), null,
                List.of(new SimpleGrantedAuthority(AccountPrincipal.ROLE_USER)));
        mvc.perform(get("/api/v1/auth/me").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().exists("X-Request-Id"));
    }
}
