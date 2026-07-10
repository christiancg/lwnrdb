package org.techhouse.conn;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Owner-side state for a forwarded transaction: the synthetic client running its buffered operations and a
 * dedicated single-thread executor. All of the session's work (start / buffer / commit / rollback) runs on
 * that one thread so the collection write locks it holds are acquired and released by the same thread — a
 * {@code ReentrantReadWriteLock} write lock is thread-owned — and two distinct sessions are genuinely
 * mutually exclusive rather than falsely sharing a reentrant lock on a shared connection thread. The executor
 * is intentionally long-lived (shut down by {@link #shutdown()} on commit/rollback/reap), so it is kept behind
 * these methods rather than exposed as a closeable resource.
 */
public record TxSession(UUID clientId, ExecutorService executor, String edgeNodeId) {
    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    public void shutdown() {
        executor.shutdown();
    }
}
