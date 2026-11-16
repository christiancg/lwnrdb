package org.techhouse.bckg_ops;

import org.techhouse.config.Globals;

final class BatchLinger {
    private BatchLinger() {
    }

    static boolean applies(long lastBatchEndedNanos, long now, int batchSize) {
        return batchSize < Globals.BACKGROUND_LINGER_EVENTS
                && now - lastBatchEndedNanos < Globals.BACKGROUND_LINGER_BUSY_WINDOW_NANOS;
    }
}
