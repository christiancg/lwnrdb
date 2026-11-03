package org.techhouse.ops.index;

import java.io.IOException;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.PendingFold;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public final class FieldIndexFolds {
    private static final Logger logger = Logger.logFor(FieldIndexFolds.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private static final Configuration configuration = Configuration.getInstance();
    @SuppressWarnings("FieldMayBeFinal")
    private static FileSystem fs = IocContainer.get(FileSystem.class);

    @FunctionalInterface
    interface AfterFold {
        void folded(DeferredFold fold, PendingFold.Outcome outcome);
    }

    private FieldIndexFolds() {
    }

    static void runHoldingLocks(List<DeferredFold> folds) throws IOException {
        for (final var fold : folds) {
            settle(fold, fs.fold(fold.pending()));
        }
    }

    public static void runAfterUnlock(List<DeferredFold> folds) throws InterruptedException {
        runAfterUnlock(folds, (_, _) -> {
        });
    }

    static void runAfterUnlock(List<DeferredFold> folds, AfterFold after) throws InterruptedException {
        for (final var fold : folds) {
            final var pending = fold.pending();
            if (!rl.tryLockIndex(pending.dbName(), pending.collName(), pending.fieldName(),
                    configuration.getTransactionLockTimeoutMs())) {
                continue;
            }
            try {
                final var outcome = fs.fold(pending);
                settle(fold, outcome);
                after.folded(fold, outcome);
            } catch (IOException e) {
                logger.warning("Could not fold the index log of "
                        + Cache.getCollectionIdentifier(pending.dbName(), pending.collName()) + "."
                        + pending.fieldName() + " into its base: " + e.getMessage()
                        + ". Its changes stay logged and the next append retries the fold.");
            } finally {
                rl.releaseIndex(pending.dbName(), pending.collName(), pending.fieldName());
            }
        }
    }

    private static void settle(DeferredFold fold, PendingFold.Outcome outcome) {
        if (outcome == PendingFold.Outcome.EMPTIED) {
            final var pending = fold.pending();
            cache.evictFieldIndex(pending.dbName(), pending.collName(), fold.identifier());
        }
    }
}
