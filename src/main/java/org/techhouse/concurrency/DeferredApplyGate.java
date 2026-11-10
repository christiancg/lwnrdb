package org.techhouse.concurrency;

import java.util.Collection;
import org.techhouse.log.Logger;

final class DeferredApplyGate {
    private static final Logger logger = Logger.logFor(DeferredApplyGate.class);
    private static volatile DeferredApplyHook hook = DeferredApplyHook.NONE;

    private DeferredApplyGate() {
    }

    static void use(DeferredApplyHook replacement) {
        hook = replacement == null ? DeferredApplyHook.NONE : replacement;
    }

    static boolean applied(String lockName) {
        return !hook.pending(lockName);
    }

    static boolean anyPending(Collection<String> lockNames) {
        final var current = hook;
        for (final var lockName : lockNames) {
            if (current.pending(lockName)) {
                return true;
            }
        }
        return false;
    }

    static void applyQuietly(String lockName) {
        final var current = hook;
        try {
            if (current.pending(lockName)) {
                current.apply(lockName);
            }
        } catch (Throwable failure) {
            logger.error("Could not apply the logged writes of " + lockName + " while granting its lock; it stays"
                    + " flagged and the next use retries", failure);
        }
    }
}
