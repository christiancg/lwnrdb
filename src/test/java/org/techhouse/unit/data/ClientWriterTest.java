package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Client;
import org.techhouse.test.TestUtils;

public class ClientWriterTest {

    @Test
    public void client_writerGetterSetter() {
        final var client = new Client("127.0.0.1");
        final var writer = new BufferedWriter(new StringWriter());

        client.setWriter(writer);

        assertSame(writer, client.getWriter());
    }

    @Test
    public void clientTracker_registerAndGetWriter() {
        final var tracker = new ClientTracker();
        final var clientId = tracker.addClient(TestUtils.clientSocket());
        final var writer = new BufferedWriter(new StringWriter());

        tracker.registerWriter(clientId, writer);

        assertSame(writer, tracker.getWriter(clientId));
    }

    @Test
    public void clientTracker_getWriter_unknownId_returnsNull() {
        final var tracker = new ClientTracker();

        assertNull(tracker.getWriter(UUID.randomUUID()));
    }

    @Test
    public void clientTracker_registerWriter_nullId_doesNotThrow() {
        final var tracker = new ClientTracker();
        final var writer = new BufferedWriter(new StringWriter());

        assertDoesNotThrow(() -> tracker.registerWriter(null, writer));
    }

    @Test
    public void clientTracker_getWriter_nullId_returnsNull() {
        final var tracker = new ClientTracker();

        assertNull(tracker.getWriter(null));
    }
}
