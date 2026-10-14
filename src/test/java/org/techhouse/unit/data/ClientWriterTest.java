package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Client;
import org.techhouse.test.TestUtils;

public class ClientWriterTest {

    @Test
    public void client_outboundGetterSetter() {
        final var client = new Client("127.0.0.1");
        final var outbound = TestUtils.inlineOutbound(new ByteArrayOutputStream());

        client.setOutbound(outbound);

        assertSame(outbound, client.getOutbound());
    }

    @Test
    public void clientTracker_registerAndGetOutbound() {
        final var tracker = new ClientTracker();
        final var clientId = tracker.addClient(TestUtils.clientSocket());
        final var outbound = TestUtils.inlineOutbound(new ByteArrayOutputStream());

        tracker.registerOutbound(clientId, outbound);

        assertSame(outbound, tracker.getOutbound(clientId));
    }

    @Test
    public void clientTracker_getOutbound_unknownId_returnsNull() {
        final var tracker = new ClientTracker();

        assertNull(tracker.getOutbound(UUID.randomUUID()));
    }

    @Test
    public void clientTracker_registerOutbound_nullId_doesNotThrow() {
        final var tracker = new ClientTracker();
        final var outbound = TestUtils.inlineOutbound(new ByteArrayOutputStream());

        assertDoesNotThrow(() -> tracker.registerOutbound(null, outbound));
    }

    @Test
    public void clientTracker_getOutbound_nullId_returnsNull() {
        final var tracker = new ClientTracker();

        assertNull(tracker.getOutbound(null));
    }
}
