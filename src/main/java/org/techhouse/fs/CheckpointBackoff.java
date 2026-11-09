package org.techhouse.fs;

import org.techhouse.config.Globals;

final class CheckpointBackoff {
    private static final int MAX_DOUBLINGS = 16;

    private CheckpointBackoff() {
    }

    static boolean waiting(WalLogState state, long nowMillis) {
        return state != null && nowMillis < state.checkpointRetryAtMillis;
    }

    static long failed(WalLogState state, long nowMillis) {
        if (state == null) {
            return 0L;
        }
        state.checkpointFailures++;
        final var delay = Math.min(Globals.WAL_CHECKPOINT_RETRY_MAX_MS,
                Globals.WAL_CHECKPOINT_RETRY_BASE_MS << Math.min(state.checkpointFailures - 1, MAX_DOUBLINGS));
        state.checkpointRetryAtMillis = nowMillis + delay;
        return delay;
    }

    static int succeeded(WalLogState state) {
        if (state == null) {
            return 0;
        }
        final var streak = state.checkpointFailures;
        state.checkpointFailures = 0;
        state.checkpointRetryAtMillis = 0L;
        return streak;
    }
}
