package dev.webhooks.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API keys authenticate each request afresh. Their authentication must never be stored in a server-side
 * session: otherwise every API call writes a session row, and the session cookie keeps the key's access
 * alive after the key is revoked.
 *
 * <p>Note: in a shared MockMvc context, an earlier request that used the {@code csrf()} test post-processor
 * leaves an HttpSession-backed CSRF repository on the filter, which creates (empty) sessions by itself. So
 * this test checks what is stored in the session and what its cookie can do, not only whether a session
 * exists; {@code AuditOperationsProcessTest} checks over real HTTP that no session cookie is issued at all.
 */
@IntegrationTest
class AuditApiKeySessionTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;

    @Test
    void apiKeyAuthenticationIsNeverStoredInASession() throws Exception {
        Account account = data.newAccount();
        long contextsBefore = storedSecurityContexts();

        MvcResult result = mvc.perform(get("/api/v1/endpoints").header("Authorization", account.bearer()))
                .andExpect(status().isOk()).andReturn();

        assertThat(storedSecurityContexts()).as("sessions holding a security context after one API-key request")
                .isEqualTo(contextsBefore);
        Cookie sessionCookie = result.getResponse().getCookie("WEBHOOKS_SESSION");
        if (sessionCookie != null) {
            jdbc.sql("UPDATE api_keys SET revoked_at = now() WHERE account_id = :a").param("a", account.id()).update();
            mvc.perform(get("/api/v1/endpoints").cookie(sessionCookie)).andExpect(status().isUnauthorized());
        }
    }

    private long storedSecurityContexts() {
        return jdbc.sql("SELECT count(*) FROM spring_session_attributes WHERE attribute_name = 'SPRING_SECURITY_CONTEXT'")
                .query(Long.class).single();
    }
}
