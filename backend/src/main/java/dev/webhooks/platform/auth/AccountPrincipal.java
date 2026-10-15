package dev.webhooks.platform.auth;

import java.io.Serializable;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The authenticated caller. Every data access is scoped to {@code accountId}. {@code credentialId}
 * identifies how they authenticated ({@code api-key:<id>} or {@code session:<user id>}); it is the
 * rate-limit key. Serializable because dashboard sessions are stored in PostgreSQL.
 */
public record AccountPrincipal(UUID accountId, String credentialId) implements AuthenticatedPrincipal, Serializable {

    public static final String ROLE_API_CLIENT = "ROLE_API_CLIENT";
    public static final String ROLE_USER = "ROLE_USER";

    /** The account id: Spring Session indexes sessions by this name (so they can be found per account). */
    @Override
    public String getName() {
        return accountId.toString();
    }

    public static AccountPrincipal forSession(UUID userId) {
        return new AccountPrincipal(userId, "session:" + userId);
    }
}
