package org.techhouse.fs;

import java.io.IOException;
import java.nio.file.Path;
import org.techhouse.log.Logger;

final class WalRewriteBarrier {
    private static final Logger logger = Logger.logFor(WalRewriteBarrier.class);
    private final WriteAheadLog wal;

    record Begun(String dbName, String collName, long page, long seq) {
    }

    WalRewriteBarrier(WriteAheadLog wal) {
        this.wal = wal;
    }

    void settle(String dbName, String collName) throws IOException {
        wal.settleBeforeRewrite(dbName, collName);
    }

    Begun begin(String dbName, String collName, long page, long lengthBefore) throws IOException {
        return new Begun(dbName, collName, page, wal.beginRewrite(dbName, collName, page, lengthBefore));
    }

    boolean landed(Begun begun, Path folder) {
        try {
            SyncedWrites.entries(folder);
            return true;
        } catch (IOException e) {
            logger.warning("Could not sync " + folder + " after rewriting page " + begun.page() + " of "
                    + WriteAheadLog.subject(begun.dbName(), begun.collName()) + ", so the rewrite is not logged as"
                    + " landed; checkpointing instead: " + e.getMessage());
        }
        try {
            wal.checkpoint(begun.dbName(), begun.collName());
        } catch (IOException e) {
            logger.error("Could not checkpoint " + WriteAheadLog.subject(begun.dbName(), begun.collName())
                    + " after rewriting page " + begun.page() + "; until its next checkpoint an unlogged append"
                    + " past the page's new end is left to the startup heals", e);
        }
        return false;
    }

    void end(Begun begun, long lengthAfter, boolean forced) throws IOException {
        wal.endRewrite(begun.dbName(), begun.collName(), begun.seq(), begun.page(), lengthAfter, forced);
    }
}
