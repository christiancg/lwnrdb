package org.techhouse.ops;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The number of script runs executing on this node right now - RUN_SCRIPT, CALL_PROCEDURE and trigger
 * dispatch alike, since they all consume the same interpreter CPU. Gossiped with the heartbeat as the
 * placement signal {@code cluster/ScriptPlacement} samples, so it is a live hint rather than an
 * accounting figure.
 */
public class ScriptLoad {
    private final AtomicInteger running = new AtomicInteger();

    public void enter() {
        running.incrementAndGet();
    }

    public void exit() {
        running.decrementAndGet();
    }

    public int current() {
        return running.get();
    }
}
