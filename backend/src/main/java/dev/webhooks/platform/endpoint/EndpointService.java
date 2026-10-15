package dev.webhooks.platform.endpoint;

import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.crypto.SecretCipher;
import dev.webhooks.platform.crypto.Tokens;
import dev.webhooks.platform.endpoint.EndpointDtos.CreateEndpointRequest;
import dev.webhooks.platform.endpoint.EndpointDtos.EndpointCreatedResponse;
import dev.webhooks.platform.endpoint.EndpointDtos.EndpointResponse;
import dev.webhooks.platform.endpoint.EndpointDtos.UpdateEndpointRequest;
import dev.webhooks.platform.ssrf.DestinationPolicy;
import java.time.Instant;
import java.util.List;
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

    public EndpointService(EndpointRepository endpoints, SecretCipher cipher, DestinationPolicy destinationPolicy) {
        this.endpoints = endpoints;
        this.cipher = cipher;
        this.destinationPolicy = destinationPolicy;
    }

    @Transactional
    public EndpointCreatedResponse create(UUID accountId, CreateEndpointRequest request) {
        String url = validateUrl(request.url());
        UUID id = UUID.randomUUID();
        String secret = Tokens.newSigningSecret();
        Endpoint endpoint = new Endpoint(id, accountId, request.name().trim(), blankToNull(request.description()), url,
                eventTypes(request.eventTypes()), true, secretPrefix(secret), Instant.now(), Instant.now());
        try {
            endpoints.insert(endpoint, cipher.encrypt(secret, id));
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        Endpoint saved = endpoints.find(accountId, id).orElseThrow();
        return new EndpointCreatedResponse(EndpointResponse.from(saved), secret);
    }

    public List<EndpointResponse> list(UUID accountId) {
        return endpoints.list(accountId).stream().map(EndpointResponse::from).toList();
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
                current.createdAt(),
                current.updatedAt());
        try {
            endpoints.update(updated);
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        return EndpointResponse.from(get(accountId, id));
    }

    @Transactional
    public void delete(UUID accountId, UUID id) {
        if (!endpoints.softDelete(accountId, id)) {
            throw ApiException.notFound("Endpoint");
        }
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
