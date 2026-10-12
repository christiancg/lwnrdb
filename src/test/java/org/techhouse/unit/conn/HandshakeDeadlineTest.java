package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.HandshakeDeadline;

public class HandshakeDeadlineTest {
    private static final long DEADLINE_MS = 50L;
    private static final long WAIT_MS = 5_000L;

    private interface SocketCheck {
        void run(Socket accepted) throws Exception;
    }

    private static void withConnectedPair(SocketCheck check) throws Exception {
        try (var server = new ServerSocket(0);
                var client = new Socket("127.0.0.1", server.getLocalPort());
                var accepted = server.accept()) {
            check.run(accepted);
            assertTrue(client.isConnected());
        }
    }

    @SuppressWarnings("BusyWait")
    private static boolean closedWithin(Socket socket, long millis) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (socket.isClosed()) {
                return true;
            }
            Thread.sleep(10);
        }
        return socket.isClosed();
    }

    @Test
    public void test_a_due_deadline_closes_the_socket_and_reports_that_it_fired() throws Exception {
        withConnectedPair(accepted -> {
            final var armed = HandshakeDeadline.arm(accepted, DEADLINE_MS, () -> true);
            assertTrue(closedWithin(accepted, WAIT_MS));
            assertTrue(armed.hasFired());
        });
    }

    @Test
    public void test_a_deadline_that_is_no_longer_due_leaves_the_socket_open() throws Exception {
        withConnectedPair(accepted -> {
            final var armed = HandshakeDeadline.arm(accepted, DEADLINE_MS, () -> false);
            assertFalse(closedWithin(accepted, DEADLINE_MS * 6));
            assertFalse(armed.hasFired());
        });
    }

    @Test
    public void test_a_disarmed_deadline_never_fires() throws Exception {
        withConnectedPair(accepted -> {
            final var armed = HandshakeDeadline.arm(accepted, DEADLINE_MS, () -> true);
            armed.disarm();
            assertFalse(closedWithin(accepted, DEADLINE_MS * 6));
            assertFalse(armed.hasFired());
        });
    }

    @Test
    public void test_an_already_closed_socket_is_left_alone() throws Exception {
        withConnectedPair(accepted -> {
            accepted.close();
            final var armed = HandshakeDeadline.arm(accepted, 0L, () -> true);
            Thread.sleep(DEADLINE_MS * 4);
            assertFalse(armed.hasFired());
        });
    }
}
