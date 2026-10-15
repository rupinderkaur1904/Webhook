package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.ssrf.AddressPolicy;
import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "127.255.255.254", "169.254.169.254", "172.16.0.1",
            "172.31.255.255", "192.0.0.8", "192.0.2.1", "192.168.1.1", "198.18.0.1", "198.51.100.7", "203.0.113.9",
            "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fc00::1", "fd00:ec2::254", "ff02::1", "2001:db8::1", "fec0::1",
            "::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:10.0.0.1", "::127.0.0.1",
            "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "2002:7f00:0001::1", "2002:0a00:0001::1", "2001:0:4136:e378::1"})
    void blocksNonPublicAddresses(String address) throws Exception {
        assertThat(AddressPolicy.isBlocked(InetAddress.getByName(address))).as(address).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8", "1.1.1.1", "93.184.216.34", "172.32.0.1", "100.128.0.1", "192.169.0.1",
            "2606:4700:4700::1111", "2a00:1450:4001:80b::200e", "::ffff:8.8.8.8", "64:ff9b::808:808",
            "2002:0808:0808::1"})
    void allowsPublicAddresses(String address) throws Exception {
        assertThat(AddressPolicy.isBlocked(InetAddress.getByName(address))).as(address).isFalse();
    }
}
