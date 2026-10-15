package dev.webhooks.platform.endpoint;

import dev.webhooks.platform.circuit.CircuitView;
import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.common.TimeWindow;
import dev.webhooks.platform.health.EndpointHealth;
import dev.webhooks.platform.health.EndpointHealthService;
import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.crypto.SecretCipher;
import dev.webhooks.platform.crypto.Tokens;
import dev.webhooks.platform.endpoint.EndpointDtos.CreateEndpointRequest;
import dev.webhooks.platform.endpoint.EndpointDtos.EndpointCreatedResponse;
import dev.webhooks.platform.endpoint.EndpointDtos.EndpointResponse;
import dev.webhooks.platform.endpoint.EndpointDtos.RotateSecretRequest;
import dev.webhooks.platform.endpoint.EndpointDtos.RotateSecretResponse;
import dev.webhooks.platform.endpoint.EndpointDtos.UpdateEndpointRequest;
import dev.webhooks.platform.ssrf.DestinationPolicy;
import java.time.Duration;
import java.time.Instant;
import dev.webhooks.platform.metrics.MetricsDtos.EndpointHealthItem;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EndpointService {

    private final EndpointRepository endpoints;
    private final SecretCipher cipher;
    private final DestinationPolicy destinationPolicy;
    private final int maxEndpointsPerAccount;
    private final EndpointHealthService health;

    public EndpointService(EndpointRepository endpoints, SecretCipher cipher, DestinationPolicy destinationPolicy,
            WebhookProperties properties, EndpointHealthService health) {
        this.health = health;
        this.endpoints = endpoints;
        this.cipher = cipher;
        this.destinationPolicy = destinationPolicy;
        this.maxEndpointsPerAccount = properties.limits().maxEndpointsPerAccount();
    }

    @Transactional
    public EndpointCreatedResponse create(UUID accountId, CreateEndpointRequest request) {
        String url = validateUrl(request.url());
        endpoints.lockAccountForCreate(accountId);
        if (endpoints.countActive(accountId) >= maxEndpointsPerAccount) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ENDPOINT_LIMIT_REACHED",
                    "An account can have at most " + maxEndpointsPerAccount + " endpoints; delete one first");
        }
        UUID id = UUID.randomUUID();
        String secret = Tokens.newSigningSecret();
        Endpoint endpoint = new Endpoint(id, accountId, request.name().trim(), blankToNull(request.description()), url,
                eventTypes(request.eventTypes()), true, secretPrefix(secret), null, CircuitView.closed(), Instant.now(), Instant.now());
        try {
            endpoints.insert(endpoint, cipher.encrypt(secret, id));
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        Endpoint saved = endpoints.find(accountId, id).orElseThrow();
        return new EndpointCreatedResponse(response(accountId, saved), secret);
    }

    public List<EndpointResponse> list(UUID accountId) {
        List<Endpoint> all = endpoints.list(accountId);
        Map<UUID, EndpointHealth> health = this.health.forEndpoints(accountId, all, TimeWindow.DAY);
        return all.stream().map(e -> EndpointResponse.from(e, health.get(e.id()))).toList();
    }

    /** Every live endpoint's health over {@code window}, worst first, then by name. */
    public List<EndpointHealthItem> healthList(UUID accountId, TimeWindow window) {
        List<Endpoint> all = endpoints.list(accountId);
        Map<UUID, EndpointHealth> health = this.health.forEndpoints(accountId, all, window);
        return all.stream()
                .map(e -> new EndpointHealthItem(e.id(), e.name(), e.circuit(), health.get(e.id())))
                .sorted(Comparator.comparing((EndpointHealthItem i) -> i.health().status().severity()).thenComparing(EndpointHealthItem::name))
                .toList();
    }

    public EndpointResponse response(UUID accountId, Endpoint endpoint) {
        return EndpointResponse.from(endpoint, health.forEndpoint(accountId, endpoint, TimeWindow.DAY));
    }

    public EndpointHealth health(UUID accountId, UUID id, TimeWindow window) {
        return health.forEndpoint(accountId, get(accountId, id), window);
    }

    public Endpoint get(UUID accountId, UUID id) {
        return endpoints.find(accountId, id).orElseThrow(() -> ApiException.notFound("Endpoint"));
    }

    @Transactional
    public EndpointResponse update(UUID accountId, UUID id, UpdateEndpointRequest request) {
        Endpoint current = get(accountId, id);
        Endpoint updated = new Endpoint(
                current.id(),
                current.accountId(),
                request.name() != null ? request.name().trim() : current.name(),
                request.description() != null ? blankToNull(request.description()) : current.description(),
                request.url() != null ? validateUrl(request.url()) : current.url(),
                request.eventTypes() != null ? eventTypes(request.eventTypes()) : current.eventTypes(),
                request.enabled() != null ? request.enabled() : current.enabled(),
                current.secretPrefix(),
                current.previousSecretExpiresAt(),
                current.circuit(),
                current.createdAt(),
                current.updatedAt());
        try {
            endpoints.update(updated);
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        return response(accountId, get(accountId, id));
    }

    @Transactional
    public void delete(UUID accountId, UUID id) {
        if (!endpoints.softDelete(accountId, id)) {
            throw ApiException.notFound("Endpoint");
        }
    }

    /**
     * Issues a new signing secret. During the grace period every delivery carries two signatures (new
     * and old), so receivers can switch secrets without rejecting anything in between.
     */
    @Transactional
    public RotateSecretResponse rotateSecret(UUID accountId, UUID id, RotateSecretRequest request) {
        int graceHours = request == null || request.gracePeriodHours() == null ? 24 : request.gracePeriodHours();
        String secret = Tokens.newSigningSecret();
        Instant previousExpiresAt = graceHours == 0 ? null : Instant.now().plus(Duration.ofHours(graceHours));
        if (!endpoints.rotateSecret(accountId, id, cipher.encrypt(secret, id), secretPrefix(secret), previousExpiresAt)) {
            throw ApiException.notFound("Endpoint");
        }
        return new RotateSecretResponse(secret, secretPrefix(secret), previousExpiresAt);
    }

    private String validateUrl(String url) {
        try {
            return destinationPolicy.validateForRegistration(url.trim()).toString();
        } catch (DestinationPolicy.InvalidDestinationException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "URL_NOT_ALLOWED", e.getMessage(),
                    List.of(new ApiException.FieldViolation("url", e.getMessage())));
        }
    }

    private static List<String> eventTypes(List<String> types) {
        return types == null ? List.of() : types.stream().distinct().sorted().toList();
    }

    private static String secretPrefix(String secret) {
        return secret.substring(0, Tokens.SIGNING_SECRET_PREFIX.length() + 6);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException nameTaken() {
        return new ApiException(HttpStatus.CONFLICT, "ENDPOINT_NAME_TAKEN", "An endpoint with this name already exists");
    }
}
