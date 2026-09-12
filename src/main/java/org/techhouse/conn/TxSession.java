package org.techhouse.conn;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * All of a session's work runs on its own single thread because a write lock is thread-owned: releasing one
 * from another thread silently strands it, and two sessions must not share one reentrant lock.
 */
public record TxSession(UUID clientId, ExecutorService executor, String edgeNodeId) {
    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    public void shutdown() {
        executor.shutdown();
    }
}
