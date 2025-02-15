package org.techhouse.unit.data;
import org.junit.jupiter.api.Test;
import org.techhouse.data.Client;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientTest {
    // Instantiation of Client sets connectionTime to current time
    @Test
    public void test_connection_time_on_instantiation() {
        Client client = new Client("127.0.0.1");
        LocalDateTime now = LocalDateTime.now();
        assertTrue(client.getConnectionTime().isBefore(now.plusSeconds(1)) && client.getConnectionTime().isAfter(now.minusSeconds(1)));
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
}