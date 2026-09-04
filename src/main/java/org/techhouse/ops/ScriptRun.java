package org.techhouse.ops;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * One script run executing on this node right now. The {@code cancelled} flag is the backing store of the
 * {@code CancellationToken} the interpreter polls, so cancelling is a plain volatile write; the thread
 * reference exists only so a run parked in the event loop wakes at once instead of waiting out its poll
 * interval.
 */
public final class ScriptRun {
    private final String runId = UUID.randomUUID().toString();
    private final ScriptRunKind kind;
    private final String database;
    private final String name;
    private final String username;
    private final UUID clientId;
    private final long startedAt = System.currentTimeMillis();
    private final Thread thread = Thread.currentThread();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    ScriptRun(ScriptRunKind kind, String database, String name, String username, UUID clientId) {
        this.kind = kind;
        this.database = database;
        this.name = name;
        this.username = username;
        this.clientId = clientId;
    }

    public String runId() {
        return runId;
    }

    public ScriptRunKind kind() {
        return kind;
    }

    public String database() {
        return database;
    }

    public String name() {
        return name;
    }

    public String username() {
        return username;
    }

    public UUID clientId() {
        return clientId;
    }

    public long startedAt() {
        return startedAt;
    }

    public Thread thread() {
        return thread;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
        LockSupport.unpark(thread);
    }
}
