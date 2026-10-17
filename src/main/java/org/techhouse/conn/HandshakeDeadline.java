package org.techhouse.conn;

import java.net.Socket;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.techhouse.log.Logger;

public final class HandshakeDeadline {
    private static final Logger logger = Logger.logFor(HandshakeDeadline.class);
    private static final ScheduledThreadPoolExecutor CLOSER = newCloser();

    private HandshakeDeadline() {
    }

    private static ScheduledThreadPoolExecutor newCloser() {
        final var executor = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("lwnrdb-handshake-deadline").daemon().factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public static Armed arm(Socket socket, long delayMillis, BooleanSupplier stillDue) {
        final var fired = new AtomicBoolean();
        final var closer = CLOSER.schedule(() -> expire(socket, fired, stillDue), Math.max(delayMillis, 0L),
                TimeUnit.MILLISECONDS);
        return new Armed(closer, fired);
    }

    private static void expire(Socket socket, AtomicBoolean fired, BooleanSupplier stillDue) {
        if (socket.isClosed() || !stillDue.getAsBoolean()) {
            return;
        }
        fired.set(true);
        ConnectionLog.warn(logger, "Closed a connection from " + socket.getRemoteSocketAddress()
                + " that was still connecting or unauthenticated at its deadline");
        SocketClosing.closeInBackground(socket);
    }

    public record Armed(ScheduledFuture<?> closer, AtomicBoolean fired) {
        public void disarm() {
            closer.cancel(false);
        }

        public boolean hasFired() {
            return fired.get();
        }
    }
}
