package org.techhouse.ops;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every script run executing on this node, keyed by run id. It is the single source of truth for "how many
 * scripts are running": {@link ScriptLoad} reads its size for the gossiped placement signal, LIST_SCRIPTS
 * reads its contents, and CANCEL_SCRIPT flips the cancellation flag of one of its entries — so the number an
 * operator sees and the number placement acts on can never disagree.
 */
public class ScriptRunRegistry {
    private final ConcurrentHashMap<String, ScriptRun> runs = new ConcurrentHashMap<>();
    private final AtomicLong cancelled = new AtomicLong();

    public ScriptRun register(ScriptRunKind kind, String database, String name, String username, UUID clientId) {
        final var run = new ScriptRun(kind, database, name, username, clientId);
        runs.put(run.runId(), run);
        return run;
    }

    public void unregister(String runId) {
        if (runId != null) {
            runs.remove(runId);
        }
    }

    /**
     * @return {@code true} when a run with this id was executing here and has been asked to stop; {@code false}
     *         when no such run is running on this node, which is also the answer for a run that has just
     *         finished.
     */
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
        return runs.size();
    }

    public long getCancelled() {
        return cancelled.get();
    }
}
