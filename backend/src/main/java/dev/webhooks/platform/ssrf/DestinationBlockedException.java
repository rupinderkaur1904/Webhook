package dev.webhooks.platform.ssrf;

import java.net.UnknownHostException;

/**
 * Raised when a destination host resolves to an address the platform refuses to contact. Extends
 * {@link UnknownHostException} so it can be thrown from an HTTP client DNS resolver.
 */
public class DestinationBlockedException extends UnknownHostException {

    public DestinationBlockedException(String message) {
        super(message);
    }
}
