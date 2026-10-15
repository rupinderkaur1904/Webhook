package dev.webhooks.platform.ssrf;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * Decides whether an IP address is a permitted webhook destination. Only globally routable unicast
 * addresses are allowed: loopback, private, shared, link-local (including cloud metadata),
 * documentation, benchmarking, multicast and reserved ranges are blocked. IPv6 forms that embed an
 * IPv4 address (mapped, compatible, NAT64, 6to4) are unwrapped and the IPv4 address is checked.
 */
public final class AddressPolicy {

    private static final List<Cidr> BLOCKED_V4 = List.of(
            Cidr.of("0.0.0.0/8"),
            Cidr.of("10.0.0.0/8"),
            Cidr.of("100.64.0.0/10"),
            Cidr.of("127.0.0.0/8"),
            Cidr.of("169.254.0.0/16"),
            Cidr.of("172.16.0.0/12"),
            Cidr.of("192.0.0.0/24"),
            Cidr.of("192.0.2.0/24"),
            Cidr.of("192.88.99.0/24"),
            Cidr.of("192.168.0.0/16"),
            Cidr.of("198.18.0.0/15"),
            Cidr.of("198.51.100.0/24"),
            Cidr.of("203.0.113.0/24"),
            Cidr.of("224.0.0.0/4"),
            Cidr.of("240.0.0.0/4"));

    private static final List<Cidr> BLOCKED_V6 = List.of(
            Cidr.of("::/128"),
            Cidr.of("::1/128"),
            Cidr.of("100::/64"),
            Cidr.of("2001::/32"),      // Teredo: embeds an obfuscated IPv4 address
            Cidr.of("2001:db8::/32"),
            Cidr.of("fc00::/7"),       // unique local, includes fd00:ec2::254 (AWS metadata)
            Cidr.of("fe80::/10"),
            Cidr.of("fec0::/10"),      // deprecated site-local
            Cidr.of("ff00::/8"));

    private static final Cidr V4_MAPPED = Cidr.of("::ffff:0:0/96");
    private static final Cidr V4_COMPATIBLE = Cidr.of("::/96");
    private static final Cidr NAT64 = Cidr.of("64:ff9b::/96");
    private static final Cidr NAT64_LOCAL = Cidr.of("64:ff9b:1::/48");
    private static final Cidr SIX_TO_FOUR = Cidr.of("2002::/16");

    private AddressPolicy() {}

    public static boolean isBlocked(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return blockedV4(bytes);
        }
        if (address instanceof Inet6Address) {
            if (V4_MAPPED.contains(bytes) || V4_COMPATIBLE.contains(bytes) || NAT64.contains(bytes)) {
                return blockedV4(Arrays.copyOfRange(bytes, 12, 16));
            }
            if (NAT64_LOCAL.contains(bytes)) {
                return true;
            }
            if (SIX_TO_FOUR.contains(bytes)) {
                return blockedV4(Arrays.copyOfRange(bytes, 2, 6));
            }
            return BLOCKED_V6.stream().anyMatch(c -> c.contains(bytes));
        }
        return true;
    }

    private static boolean blockedV4(byte[] bytes) {
        if (bytes[0] == (byte) 255 && bytes[1] == (byte) 255 && bytes[2] == (byte) 255 && bytes[3] == (byte) 255) {
            return true;
        }
        return BLOCKED_V4.stream().anyMatch(c -> c.contains(bytes));
    }

    record Cidr(byte[] network, int prefixLength) {

        static Cidr of(String notation) {
            int slash = notation.indexOf('/');
            try {
                byte[] network = InetAddress.getByName(notation.substring(0, slash)).getAddress();
                if (notation.contains(":") && network.length == 4) {
                    // Java folds "::ffff:a.b.c.d" literals to IPv4; keep the 16-byte form here.
                    byte[] v6 = new byte[16];
                    v6[10] = (byte) 0xff;
                    v6[11] = (byte) 0xff;
                    System.arraycopy(network, 0, v6, 12, 4);
                    network = v6;
                }
                return new Cidr(network, Integer.parseInt(notation.substring(slash + 1)));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(notation, e);
            }
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xff << (8 - remainingBits)) & 0xff;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
