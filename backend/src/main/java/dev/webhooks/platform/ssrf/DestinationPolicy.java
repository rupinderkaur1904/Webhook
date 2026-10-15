package dev.webhooks.platform.ssrf;

import dev.webhooks.platform.config.WebhookProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Validates webhook destination URLs. Used twice: when an endpoint is registered or updated (fast
 * feedback) and before every send. The authoritative address check at send time happens in
 * {@link SsrfDnsResolver}, which governs the exact addresses the HTTP client connects to.
 */
@Component
public class DestinationPolicy {

    private final Set<String> allowedHosts;

    public DestinationPolicy(WebhookProperties properties) {
        this.allowedHosts = properties.ssrf().allowedHosts().stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Validates syntax, scheme and allowlist, and checks that every resolved address is public. */
    public URI validateForRegistration(String url) throws InvalidDestinationException {
        URI uri = validateSyntax(url);
        if (!isAllowlisted(uri)) {
            String host = bareHost(uri);
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new InvalidDestinationException("Host '" + host + "' does not resolve");
            }
            for (InetAddress address : addresses) {
                if (AddressPolicy.isBlocked(address)) {
                    throw new InvalidDestinationException(
                            "Host resolves to a private, loopback, link-local or reserved address");
                }
            }
        }
        return uri;
    }

    /** Checks done before every send; DNS-based checks are left to {@link SsrfDnsResolver}. */
    public URI validateForSend(String url) throws InvalidDestinationException {
        return validateSyntax(url);
    }

    public boolean isAllowlistedHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return allowedHosts.stream().anyMatch(entry -> entry.equals(normalized) || entry.startsWith(normalized + ":"));
    }

    private URI validateSyntax(String url) throws InvalidDestinationException {
        if (url == null || url.isBlank()) {
            throw new InvalidDestinationException("URL is required");
        }
        if (url.length() > 2048) {
            throw new InvalidDestinationException("URL must be at most 2048 characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidDestinationException("URL is not valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new InvalidDestinationException("URL scheme must be https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidDestinationException("URL must include a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidDestinationException("URL must not contain credentials");
        }
        if (uri.getRawFragment() != null) {
            throw new InvalidDestinationException("URL must not contain a fragment");
        }
        boolean allowlisted = isAllowlisted(uri);
        if (!allowlisted && isAllowlistedHost(bareHost(uri))) {
            // The resolver trusts allowlisted hosts by name, so the port must be checked here.
            throw new InvalidDestinationException("This host is only allowed on its configured port");
        }
        if (scheme.equals("http") && !allowlisted) {
            throw new InvalidDestinationException("URL scheme must be https");
        }
        if (!allowlisted && isIpLiteral(uri)) {
            try {
                if (AddressPolicy.isBlocked(InetAddress.getByName(bareHost(uri)))) {
                    throw new InvalidDestinationException(
                            "Host resolves to a private, loopback, link-local or reserved address");
                }
            } catch (UnknownHostException e) {
                throw new InvalidDestinationException("URL host is not valid");
            }
        }
        return uri;
    }

    private boolean isAllowlisted(URI uri) {
        String host = bareHost(uri).toLowerCase(Locale.ROOT);
        int port = effectivePort(uri);
        return allowedHosts.contains(host) || allowedHosts.contains(host + ":" + port);
    }

    private static boolean isIpLiteral(URI uri) {
        String host = bareHost(uri);
        return host.contains(":") || host.matches("[0-9.]+");
    }

    static String bareHost(URI uri) {
        String host = uri.getHost();
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    /** A destination URL that must not be used, with a message safe to show to the user. */
    public static class InvalidDestinationException extends Exception {
        public InvalidDestinationException(String message) {
            super(message);
        }
    }
}
