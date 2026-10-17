package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.ssrf.AddressPolicy;
import dev.webhooks.platform.ssrf.DestinationPolicy;
import dev.webhooks.platform.ssrf.DestinationPolicy.InvalidDestinationException;
import dev.webhooks.platform.ssrf.SsrfDnsResolver;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.worker.AttemptOutcome;
import dev.webhooks.platform.worker.HttpAttemptResult;
import dev.webhooks.platform.worker.WebhookHttpClient;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Audit (C9, SSRF): fills gaps in {@link DestinationPolicyTest} / {@link AddressPolicyTest}. Uses
 * only local targets: an in-process listener on 127.0.0.1 and literal addresses that are never
 * contacted unless the policy is broken.
 */
class AuditSsrfMatrixTest {

    static final DestinationPolicy POLICY = new DestinationPolicy(properties(List.of("simulator:9090")));
    static final DestinationPolicy NO_ALLOWLIST = new DestinationPolicy(properties(List.of()));

    static TestReceiver receiver;
    static WebhookHttpClient client;

    static WebhookProperties properties(List<String> allowed) {
        return new WebhookProperties(null, null,
                new WebhookProperties.Worker(true, Duration.ofMillis(500), 8, 4, Duration.ofSeconds(60),
                        Duration.ofSeconds(5), Duration.ofSeconds(15), 10),
                new WebhookProperties.Delivery(Duration.ofSeconds(1), Duration.ofSeconds(2), 2048),
                null, new WebhookProperties.Ssrf(allowed), null, null, null, null, null, null, null);
    }

    @BeforeAll
    static void start() throws Exception {
        receiver = new TestReceiver();
        client = new WebhookHttpClient(properties(List.of()), NO_ALLOWLIST);
    }

    @AfterAll
    static void stop() throws Exception {
        client.destroy();
        receiver.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Alternative IPv4 spellings of loopback / any
            "https://0x7f000001/", "https://017700000001/", "https://127.1/", "https://127.0.1/",
            "https://0/", "https://0.0.0.0/", "https://0x7f.0x0.0x0.0x1/", "https://127.000.000.001/",
            // Host name forms
            "https://localhost./", "https://LOCALHOST/", "https://127.0.0.1./", "https://localhost:443/",
            // Userinfo, fragment and authority tricks
            "https://example.com@127.0.0.1/", "https://good.example:pw@169.254.169.254/", "https://127.0.0.1#@example.com/",
            "https://127.0.0.1%23@example.com/", "https://127.0.0.1\\@example.com/",
            // Private, shared and link-local IPv4
            "https://10.255.255.255/", "https://172.16.0.1/", "https://192.168.0.1:8443/", "https://100.64.0.1/",
            "https://100.127.255.254/", "https://169.254.169.254/latest/meta-data/", "https://169.254.0.1/",
            "https://198.18.0.1/", "https://224.0.0.1/", "https://255.255.255.255/",
            // IPv6 loopback, unspecified, ULA, link-local, metadata
            "https://[::1]/", "https://[0:0:0:0:0:0:0:1]/", "https://[::]/", "https://[fc00::1]/", "https://[fd12:3456::1]/",
            "https://[fd00:ec2::254]/", "https://[fe80::1]/", "https://[fe80::1%25eth0]/", "https://[ff02::1]/",
            // IPv6 forms embedding a blocked IPv4 address
            "https://[::ffff:127.0.0.1]/", "https://[::ffff:7f00:1]/", "https://[0:0:0:0:0:ffff:127.0.0.1]/",
            "https://[::ffff:a9fe:a9fe]/", "https://[::127.0.0.1]/", "https://[64:ff9b::169.254.169.254]/",
            "https://[64:ff9b::7f00:1]/", "https://[64:ff9b:1::a00:1]/", "https://[2002:a9fe:a9fe::1]/",
            "https://[2001:0:4136:e378:8000:63bf:3fff:fdd2]/",
            // Allowlist tricks: wrong port, trailing dot, userinfo
            "http://simulator:9091/", "https://simulator/", "http://simulator.:9090/", "http://simulator:9090@127.0.0.1/",
            // Schemes
            "gopher://example.com/", "file:///etc/passwd", "jar:https://example.com!/", "HTTP://example.com/"})
    void registrationRejects(String url) {
        assertThatThrownBy(() -> POLICY.validateForRegistration(url)).as(url)
                .isInstanceOf(InvalidDestinationException.class);
    }

    @Test
    void allowlistIsExactHostAndPortAndCaseInsensitive() throws Exception {
        assertThat(POLICY.validateForRegistration("http://SIMULATOR:9090/receivers/a").getHost()).isEqualTo("SIMULATOR");
        assertThatThrownBy(() -> POLICY.validateForRegistration("http://simulator:9090.evil/"))
                .isInstanceOf(InvalidDestinationException.class);
    }

    /**
     * IPv4-translated addresses ({@code ::ffff:0:a.b.c.d}, RFC 2765 SIIT) embed an IPv4 address like
     * the mapped form does. Not routable on ordinary hosts, but a SIIT translator would forward them.
     */
    @Test
    void ipv4TranslatedFormIsBlocked() throws Exception {
        assertThat(AddressPolicy.isBlocked(InetAddress.getByName("::ffff:0:7f00:1"))).isTrue();
        assertThat(AddressPolicy.isBlocked(InetAddress.getByName("::ffff:0:a9fe:a9fe"))).isTrue();
    }

    /**
     * The JDK reads a leading-zero literal as decimal ({@code 0177.0.0.1} is 177.0.0.1, a public address),
     * unlike inet_aton's octal. That is safe here because the address that is checked is the address
     * that is connected to; the URL never reaches 127.0.0.1.
     */
    @Test
    void leadingZeroLiteralIsReadAsDecimalAndCheckedAsSuch() throws Exception {
        assertThat(new SsrfDnsResolver(NO_ALLOWLIST).resolve("0177.0.0.1"))
                .containsExactly(InetAddress.getByAddress(new byte[] {(byte) 177, 0, 0, 1}));
        int before = receiver.requests().size();
        HttpAttemptResult result = client.post(URI.create("http://0177.0.0.1:" + receiver.port() + "/hook"), Map.of(),
                "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(result.outcome()).isNotEqualTo(AttemptOutcome.SUCCESS);
        assertThat(receiver.requests()).hasSize(before);
    }

    /** Send time: the resolver itself never hands the HTTP client a blocked address, whatever the spelling. */
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "[::1]", "::1", "localhost", "localhost.", "0x7f.1", "2130706433",
            "127.1", "0", "0.0.0.0", "169.254.169.254", "::ffff:127.0.0.1", "fd00:ec2::254", "[fe80::1]", "10.0.0.1",
            "64:ff9b::a9fe:a9fe"})
    void sendTimeResolverRefusesEveryBlockedSpelling(String host) {
        assertThatThrownBy(() -> new SsrfDnsResolver(NO_ALLOWLIST).resolve(host)).as(host)
                .isInstanceOf(UnknownHostException.class);
    }

    /**
     * The real outbound client, with no allowlist and the pre-send syntax check skipped, still refuses
     * to connect to a local listener: the resolver is applied to IP literals too (the client does not
     * short-circuit them), so a URL that slipped past registration cannot reach an internal address.
     */
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "[::1]", "localhost", "127.1", "2130706433", "0x7f.1"})
    void realClientRefusesLocalListenerEvenWithoutThePreSendCheck(String host) {
        int before = receiver.requests().size();
        HttpAttemptResult result = client.post(URI.create("http://" + host + ":" + receiver.port() + "/hook"),
                Map.of("webhook-id", "x"), "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(result.outcome()).as(host).isIn(AttemptOutcome.DESTINATION_BLOCKED, AttemptOutcome.DNS_ERROR);
        assertThat(receiver.requests()).hasSize(before);
    }

    @Test
    void realClientNeverFollowsRedirectsEvenToAllowedTargets() throws Exception {
        WebhookHttpClient allowed = new WebhookHttpClient(properties(List.of("127.0.0.1")),
                new DestinationPolicy(properties(List.of("127.0.0.1"))));
        try {
            receiver.respondWith(r -> r.path().equals("/redirect")
                    ? new TestReceiver.Response(307, Map.of("Location", "http://169.254.169.254/latest/meta-data/"), "", 0)
                    : TestReceiver.Response.status(200));
            HttpAttemptResult result = allowed.post(URI.create(receiver.url("/redirect")), Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(result.outcome()).isEqualTo(AttemptOutcome.HTTP_REDIRECT);
            assertThat(result.statusCode()).isEqualTo(307);
        } finally {
            receiver.respondWith(r -> TestReceiver.Response.status(200));
            allowed.destroy();
        }
    }
}
