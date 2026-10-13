package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.SocketServer;
import org.techhouse.test.TestUtils;

public class SocketServerBindAddressTest {
    private static final long LISTEN_DEADLINE_MS = 10_000L;

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ServerSocket listeningSocketOf(SocketServer server) throws Exception {
        final var deadline = System.currentTimeMillis() + LISTEN_DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            final var socket = TestUtils.getPrivateField(server, "serverSocket", ServerSocket.class);
            if (socket != null && socket.isBound()) {
                return socket;
            }
            Thread.onSpinWait();
        }
        return null;
    }

    @Test
    public void test_bind_address_key_is_honoured() throws Exception {
        final var port = freePort();
        final var server = new SocketServer(port, "127.0.0.1", null);
        try (var executor = Executors.newSingleThreadExecutor()) {
            final Future<?> serving = executor.submit(server::serve);
            try {
                final var listening = listeningSocketOf(server);
                assertNotNull(listening);
                assertTrue(listening.getInetAddress().isLoopbackAddress(), listening.getInetAddress().toString());
                try (var client = new Socket("127.0.0.1", port)) {
                    assertTrue(client.isConnected());
                }
            } finally {
                server.stopAccepting();
                serving.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void test_a_blank_bind_address_listens_on_every_interface() throws Exception {
        final var server = new SocketServer(freePort(), " ", null);
        try (var executor = Executors.newSingleThreadExecutor()) {
            final Future<?> serving = executor.submit(server::serve);
            try {
                final var listening = listeningSocketOf(server);
                assertNotNull(listening);
                assertTrue(listening.getInetAddress().isAnyLocalAddress(), listening.getInetAddress().toString());
            } finally {
                server.stopAccepting();
                serving.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void test_an_unresolvable_bind_address_stops_serving_instead_of_hanging() throws Exception {
        final var server = new SocketServer(freePort(), "no-such-host.invalid", null);
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(server::serve).get(30, TimeUnit.SECONDS);
        }
        assertNull(TestUtils.getPrivateField(server, "serverSocket", ServerSocket.class));
    }
}
