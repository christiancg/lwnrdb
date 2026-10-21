package org.techhouse.ops;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ScriptRunRegistry {
    private final ConcurrentHashMap<String, ScriptRun> runs = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong cancelled = new AtomicLong();

    public ScriptRun register(ScriptRunKind kind, String database, String name, String username, UUID clientId) {
        final var run = new ScriptRun(kind, database, name, username, clientId);
        runs.put(run.runId(), run);
        active.incrementAndGet();
        return run;
    }

    public void unregister(String runId) {
        if (runId != null) {
            if (runs.remove(runId) != null) {
                active.decrementAndGet();
            }
        }
    }

    public boolean cancel(String runId) {
        if (runId == null) {
            return false;
        }
        final var run = runs.get(runId);
        if (run == null) {
            return false;
        }
        run.cancel();
        cancelled.incrementAndGet();
        return true;
    }

    public List<ScriptRun> list() {
        return List.copyOf(runs.values());
    }

    public int size() {
        return active.get();
    }

    public long getCancelled() {
        return cancelled.get();
    }
}
