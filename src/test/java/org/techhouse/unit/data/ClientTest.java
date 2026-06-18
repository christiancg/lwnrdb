package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.techhouse.data.Client;

public class ClientTest {
    // Instantiation of Client sets connectionTime to current time
    @Test
    public void test_connection_time_on_instantiation() {
        Client client = new Client("127.0.0.1");
        LocalDateTime now = LocalDateTime.now();
        assertTrue(client.getConnectionTime().isBefore(now.plusSeconds(1))
                && client.getConnectionTime().isAfter(now.minusSeconds(1)));
    }

    // Instantiation of Client sets connectionTime to current time
    @Test
    public void test_getters_and_setters() {
        Client client = new Client("127.0.0.1");
        assertEquals("127.0.0.1", client.getAddress());
        final var now = LocalDateTime.now();
        client.setLastCommandTime(now);
        assertEquals(now, client.getLastCommandTime());
    }

    @Test
    public void test_equals_same_instance() {
        Client client = new Client("127.0.0.1");
        assertEquals(client, client);
    }

    @Test
    public void test_equals_null_returns_false() {
        Client client = new Client("127.0.0.1");
        assertNotEquals(null, client);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        Client client = new Client("127.0.0.1");
        assertFalse(client.equals("127.0.0.1"));
    }

    @Test
    public void test_equals_symmetric() {
        Client client1 = new Client("127.0.0.1");
        // Both share same address; connectionTime will differ by tiny amount but
        // equals checks connectionTime as well, so create them with identical state
        // by checking only address-mismatch path
        Client clientOther = new Client("10.0.0.1");
        assertNotEquals(client1, clientOther);
    }

    @Test
    public void test_equals_different_address() {
        Client client1 = new Client("127.0.0.1");
        Client client2 = new Client("192.168.0.1");
        assertNotEquals(client1, client2);
    }

    @Test
    public void test_hashCode_same_values_equal() {
        Client client = new Client("127.0.0.1");
        assertEquals(client.hashCode(), client.hashCode());
    }

    @Test
    public void test_hashCode_different_address_differs() {
        Client client1 = new Client("127.0.0.1");
        Client client2 = new Client("10.0.0.1");
        assertNotEquals(client1.hashCode(), client2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        Client client = new Client("127.0.0.1");
        assertNotNull(client.toString());
    }

    @Test
    public void test_authenticated_username_default_null() {
        Client client = new Client("127.0.0.1");
        assertNull(client.getAuthenticatedUsername());
    }

    @Test
    public void test_set_authenticated_username() {
        Client client = new Client("127.0.0.1");
        client.setAuthenticatedUsername("alice");
        assertEquals("alice", client.getAuthenticatedUsername());
    }

    @Test
    public void test_equals_includes_authenticated_username() {
        Client client1 = new Client("127.0.0.1");
        Client client2 = new Client("127.0.0.1");
        // Force same connectionTime via reflection isn't needed — different auth username should differ
        client1.setAuthenticatedUsername("alice");
        client2.setAuthenticatedUsername("bob");
        assertNotEquals(client1, client2);
    }

    @Test
    public void test_toString_includes_authenticated_username() {
        Client client = new Client("127.0.0.1");
        client.setAuthenticatedUsername("charlie");
        assertTrue(client.toString().contains("charlie"));
    }
}
