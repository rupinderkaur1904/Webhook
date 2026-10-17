package dev.webhooks.platform.config;

import java.util.List;
import org.apache.catalina.util.NetMask;
import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Believes {@code X-Forwarded-For} and {@code X-Forwarded-Proto} only when the TCP peer is one of the
 * configured reverse proxies ({@code webhooks.proxy.trusted-proxies}, CIDR ranges).
 *
 * <p>Tomcat's {@link RemoteIpValve} walks {@code X-Forwarded-For} from the right, skipping addresses
 * inside the trusted ranges; the first address outside them is the client. A client can prepend
 * anything it likes to the header, but those values sit to the left of the address the proxy appended
 * and are never reached. A request whose peer is not a trusted proxy keeps its peer address, and its
 * forwarding headers are ignored. {@code X-Forwarded-Host} is never believed.
 *
 * <p>The client address drives the login throttle, and the forwarded protocol decides whether the
 * request counts as HTTPS (HSTS). With no trusted proxies configured, no valve is installed.
 */
@Configuration
public class TrustedProxyConfig {

    @Bean
    WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> trustedProxyValve(WebhookProperties properties) {
        List<String> ranges = normalize(properties.proxy().trustedProxies());
        return factory -> {
            if (ranges.isEmpty()) {
                return;
            }
            factory.addEngineValves(valve(ranges));
        };
    }

    static RemoteIpValve valve(List<String> ranges) {
        RemoteIpValve valve = new RemoteIpValve();
        // Every entry contains '/', so Tomcat parses the list as CIDR ranges, not as a regex.
        valve.setInternalProxies(String.join(", ", ranges));
        valve.setTrustedProxies(null);
        valve.setRemoteIpHeader("X-Forwarded-For");
        valve.setProtocolHeader("X-Forwarded-Proto");
        valve.setProtocolHeaderHttpsValue("https");
        valve.setPortHeader(null);
        valve.setHostHeader(null);
        return valve;
    }

    /**
     * Validates each entry and turns single addresses into /32 or /128 ranges. Fails startup on
     * anything that is not an address or CIDR range, so a typo cannot silently disable the setting.
     */
    public static List<String> normalize(List<String> entries) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
                .map(String::trim)
                .filter(e -> !e.isEmpty())
                .map(e -> e.contains("/") ? e : e + (e.contains(":") ? "/128" : "/32"))
                .peek(TrustedProxyConfig::check)
                .toList();
    }

    private static void check(String cidr) {
        try {
            // Literal addresses only: NetMask would otherwise resolve a host name through DNS.
            if (!cidr.matches("[0-9A-Fa-f.:]+/\\d{1,3}")) {
                throw new IllegalArgumentException("not a literal address");
            }
            new NetMask(cidr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("webhooks.proxy.trusted-proxies: '" + cidr
                    + "' is not an IP address or CIDR range", e);
        }
    }
}
