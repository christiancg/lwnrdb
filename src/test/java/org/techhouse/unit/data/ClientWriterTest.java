package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Client;

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
        final var socket = Mockito.mock(Socket.class);
        final var addr = Mockito.mock(InetAddress.class);
        Mockito.when(socket.getInetAddress()).thenReturn(addr);
        Mockito.when(addr.getHostAddress()).thenReturn("127.0.0.1");

        final var clientId = tracker.addClient(socket);
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
