package org.techhouse.fs;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public final class PendingForce {
    static final PendingForce NONE = new PendingForce();

    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private Thread forcing;

    private PendingForce() {
    }

    static PendingForce start(Path file) {
        if (!SyncedWrites.enabled()) {
            return NONE;
        }
        final var pending = new PendingForce();
        pending.forcing = Thread.ofVirtual().start(() -> pending.forceQuietly(file));
        return pending;
    }

    private void forceQuietly(Path file) {
        try {
            SyncedWrites.content(file);
        } catch (IOException e) {
            failure.set(e);
        }
    }

    public void await() throws IOException {
        if (forcing == null) {
            return;
        }
        try {
            forcing.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while forcing a deferred append");
        }
        final var failed = failure.get();
        if (failed != null) {
            throw failed;
        }
    }
}
