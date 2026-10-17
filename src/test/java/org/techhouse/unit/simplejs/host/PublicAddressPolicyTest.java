package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.simplejs.host.PublicAddressPolicy;

public class PublicAddressPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.255.0.9", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "100.127.255.255", "0.0.0.0", "0.1.2.3", "192.0.0.8", "198.18.0.1",
            "198.19.255.255", "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255", "::1", "::", "fc00::1",
            "fd00::1", "fe80::1", "febf::1", "fec0::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::7f00:1",
            "::a00:1", "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "2002:7f00:1::", "2002:a00:1::1"})
    public void test_non_public_addresses_are_refused(String literal) {
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.ofLiteral(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.0", "172.15.255.255", "172.32.0.0",
            "192.0.1.1", "198.17.255.255", "198.20.0.0", "223.255.255.255", "2606:4700:4700::1111", "::ffff:8.8.8.8",
            "64:ff9b::808:808", "2002:808:808::1", "2001:4860:4860::8888"})
    public void test_public_addresses_are_allowed(String literal) {
        assertTrue(PublicAddressPolicy.isPublic(InetAddress.ofLiteral(literal)), literal);
    }

    @Test
    public void test_decimal_and_hex_ipv4_spellings_are_normalised_before_the_check() throws UnknownHostException {
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName("2130706433")));
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName("127.1")));
    }

    @Test
    public void test_null_is_not_public() {
        assertFalse(PublicAddressPolicy.isPublic(null));
    }
}
