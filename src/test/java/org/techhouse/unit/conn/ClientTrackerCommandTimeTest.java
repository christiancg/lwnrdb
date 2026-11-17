package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.data.Client;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class ClientTrackerCommandTimeTest {
    private final Configuration configuration = Configuration.getInstance();
    private int originalMaxConnections;
    private ClientTracker clientTracker;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        originalMaxConnections = configuration.getMaxConnections();
        TestUtils.setPrivateField(configuration, "maxConnections", 0);
        clientTracker = new ClientTracker();
    }

    @AfterEach
    void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(configuration, "maxConnections", originalMaxConnections);
    }

    private UUID connect() {
        final var socket = Mockito.mock(Socket.class);
        final var address = Mockito.mock(InetAddress.class);
        Mockito.when(socket.getInetAddress()).thenReturn(address);
        Mockito.when(address.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    private Client clientOf(UUID clientId) throws NoSuchFieldException, IllegalAccessException {
        final var clientsType = new ReflectionUtils.TypeToken<java.util.Map<UUID, Client>>() {
        };
        return TestUtils.getPrivateField(clientTracker, "clients", clientsType).get(clientId);
    }

    @Test
    public void millisSinceLastCommandIsZeroBeforeAnyCommand() {
        final var clientId = connect();

        assertEquals(0, clientTracker.millisSinceLastCommand(clientId));
        assertEquals(0, clientTracker.millisSinceLastCommand(null));
        assertEquals(0, clientTracker.millisSinceLastCommand(UUID.randomUUID()));
    }

    @Test
    public void millisSinceLastCommandIsMeasuredFromTheStoredNanos()
            throws NoSuchFieldException, IllegalAccessException {
        final var clientId = connect();
        clientOf(clientId).setLastCommandNanos(System.nanoTime() - TimeUnit.SECONDS.toNanos(5));

        final var elapsed = clientTracker.millisSinceLastCommand(clientId);

        assertTrue(elapsed >= 5000 && elapsed < 60_000, String.valueOf(elapsed));
    }

    @Test
    public void updateLastCommandTimeRestartsTheIdleClock() throws NoSuchFieldException, IllegalAccessException {
        final var clientId = connect();
        clientOf(clientId).setLastCommandNanos(System.nanoTime() - TimeUnit.SECONDS.toNanos(5));

        clientTracker.updateLastCommandTime(clientId);

        assertTrue(clientTracker.millisSinceLastCommand(clientId) < 5000);
    }

    @Test
    public void aForwardedTransactionSessionStartsItsIdleClockAtRegistration() {
        final var session = clientTracker.registerTxSession("idle-clock", "admin", "edge");
        try {
            assertTrue(clientTracker.millisSinceLastCommand(session.clientId()) < 5000);
        } finally {
            clientTracker.removeTxSession("idle-clock");
        }
    }

    @Test
    public void forEachOutboundVisitsOnlyConnectionsWithAQueue() {
        final var withQueue = connect();
        connect();
        final var queue = new OutboundQueue(new ByteArrayOutputStream(), () -> {
        }, 100, Runnable::run, () -> {
        });
        clientTracker.registerOutbound(withQueue, queue);
        final var visited = new HashMap<UUID, OutboundQueue>();

        clientTracker.forEachOutbound(visited::put);

        assertEquals(1, visited.size());
        assertEquals(queue, visited.get(withQueue));
    }
}
