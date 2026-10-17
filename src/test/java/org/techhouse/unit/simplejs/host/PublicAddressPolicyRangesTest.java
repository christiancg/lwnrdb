package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.host.PublicAddressPolicy;

public class PublicAddressPolicyRangesTest {
    @Test
    public void test_teredo_and_local_use_nat64_are_not_public() {
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.ofLiteral("2001:0:4136:e378:8000:63bf:3fff:fdd2")));
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.ofLiteral("64:ff9b:1::a00:1")));
    }

    @Test
    public void test_ordinary_global_ipv6_stays_public() {
        assertTrue(PublicAddressPolicy.isPublic(InetAddress.ofLiteral("2001:4860:4860::8888")));
        assertTrue(PublicAddressPolicy.isPublic(InetAddress.ofLiteral("2606:4700:4700::1111")));
    }
}
