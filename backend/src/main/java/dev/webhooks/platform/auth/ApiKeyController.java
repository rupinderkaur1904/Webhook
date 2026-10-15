package dev.webhooks.platform.auth;

import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.crypto.Tokens;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API keys for producers. Dashboard session only; a key is shown in plaintext once, at creation. */
@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

    static final int MAX_ACTIVE_KEYS = 20;

    public record CreateApiKeyRequest(@NotBlank @Size(max = 100) String name) {}

    public record ApiKeyCreatedResponse(UUID id, String name, String key, String prefix) {}

    public record ApiKeyListResponse(List<ApiKeyRepository.ApiKey> items) {}

    private final ApiKeyRepository apiKeys;

    public ApiKeyController(ApiKeyRepository apiKeys) {
        this.apiKeys = apiKeys;
    }

    @GetMapping
    ApiKeyListResponse list(@AuthenticationPrincipal AccountPrincipal principal) {
        return new ApiKeyListResponse(apiKeys.list(principal.accountId()));
    }

    @PostMapping
    @Transactional
    ResponseEntity<ApiKeyCreatedResponse> create(@AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody CreateApiKeyRequest request) {
        if (apiKeys.countActive(principal.accountId()) >= MAX_ACTIVE_KEYS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "API_KEY_LIMIT_REACHED",
                    "An account can have at most " + MAX_ACTIVE_KEYS + " active API keys; revoke one first");
        }
        String key = Tokens.newApiKey();
        String prefix = key.substring(0, 12);
        UUID id = apiKeys.insert(principal.accountId(), request.name().trim(), prefix, Tokens.sha256(key));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiKeyCreatedResponse(id, request.name().trim(), key, prefix));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> revoke(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        if (!apiKeys.revoke(principal.accountId(), id)) {
            throw ApiException.notFound("API key");
        }
        return ResponseEntity.noContent().build();
    }
}
