package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.cluster.NodeAddress;

public class NodeAddressTest {

    @Test
    public void test_parse_valid_address() {
        final var address = NodeAddress.parse("host.example:9990");
        assertEquals("host.example", address.getHost());
        assertEquals(9990, address.getPort());
    }

    @Test
    public void test_parse_trims_whitespace() {
        final var address = NodeAddress.parse("  10.0.0.1:7000  ");
        assertEquals("10.0.0.1", address.getHost());
        assertEquals(7000, address.getPort());
    }

    @Test
    public void test_parse_rejects_missing_port() {
        assertThrows(IllegalArgumentException.class, () -> NodeAddress.parse("hostonly"));
    }

    @Test
    public void test_parse_rejects_trailing_separator() {
        assertThrows(IllegalArgumentException.class, () -> NodeAddress.parse("host:"));
    }

    @Test
    public void test_parse_rejects_non_numeric_port() {
        assertThrows(IllegalArgumentException.class, () -> NodeAddress.parse("host:abc"));
    }

    @Test
    public void test_to_string_round_trips() {
        final var address = new NodeAddress("host", 9990);
        assertEquals("host:9990", address.toString());
        assertEquals(address, NodeAddress.parse(address.toString()));
    }

    @Test
    public void test_equals_and_hashcode() {
        final var a = new NodeAddress("host", 9990);
        final var b = new NodeAddress("host", 9990);
        final var c = new NodeAddress("host", 9991);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertNotEquals(a, "host:9990");
    }
}
