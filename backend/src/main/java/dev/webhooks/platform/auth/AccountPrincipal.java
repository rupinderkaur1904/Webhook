package dev.webhooks.platform.auth;

import java.util.UUID;

/** The authenticated caller. Every data access is scoped to {@code accountId}. */
public record AccountPrincipal(UUID accountId, String credentialId) {}
