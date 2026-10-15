package dev.webhooks.platform.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import dev.webhooks.platform.auth.AccountPrincipal;
import dev.webhooks.platform.crypto.Tokens;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Creates isolated accounts so tests never depend on each other's data. */
@Component
public class TestData {

    private final JdbcClient jdbc;

    public TestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Account(UUID id, String apiKey) {
        public String bearer() {
            return "Bearer " + apiKey;
        }

        /** A logged-in dashboard session for this account, with a valid CSRF token. */
        public RequestPostProcessor session() {
            var auth = UsernamePasswordAuthenticationToken.authenticated(AccountPrincipal.forSession(id), null,
                    List.of(new SimpleGrantedAuthority(AccountPrincipal.ROLE_USER)));
            return request -> csrf().postProcessRequest(authentication(auth).postProcessRequest(request));
        }
    }

    public Account newAccount() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, '{noop}unused')")
                .param("id", id)
                .param("email", "user-" + id + "@test.local")
                .update();
        String key = Tokens.newApiKey();
        jdbc.sql("INSERT INTO api_keys (account_id, name, key_prefix, key_hash) VALUES (:id, 'test', :prefix, :hash)")
                .param("id", id)
                .param("prefix", key.substring(0, 12))
                .param("hash", Tokens.sha256(key))
                .update();
        return new Account(id, key);
    }

    /** Deletes all queue work so worker-level tests see only their own deliveries. */
    public void clearDeliveries() {
        jdbc.sql("DELETE FROM deliveries").update();
    }
}
