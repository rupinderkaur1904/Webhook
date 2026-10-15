package dev.webhooks.platform.ssrf;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;

/**
 * DNS resolver for the outbound webhook client. It resolves the host and rejects the connection if
 * any resolved address is blocked. Because the client connects only to the addresses returned
 * here, a DNS answer that changes between "check" and "connect" (DNS rebinding) cannot slip
 * through.
 */
public class SsrfDnsResolver implements DnsResolver {

    private final DestinationPolicy policy;

    public SsrfDnsResolver(DestinationPolicy policy) {
        this.policy = policy;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses = InetAddress.getAllByName(bare);
        if (policy.isAllowlistedHost(bare)) {
            return addresses;
        }
        for (InetAddress address : addresses) {
            if (AddressPolicy.isBlocked(address)) {
                throw new DestinationBlockedException("Destination resolves to a blocked address");
            }
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }
}
