package org.techhouse.test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ioc.IocContainer;

public final class IndexRaceSupport {
    private static final int READS = 500;
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);

    public interface Read {
        void run() throws Exception;
    }

    private IndexRaceSupport() {
    }

    public static <T> void readWhileTheIndexerChurns(String dbName, String collName, String fieldName,
            Class<T> indexType, Read read) throws Exception {
        final var stop = new AtomicBoolean();
        final var writerFailure = new AtomicReference<Throwable>();
        final var writer = new Thread(() -> churn(dbName, collName, fieldName, indexType, stop, writerFailure),
                "index-churn");
        writer.start();
        try {
            for (var i = 0; i < READS; i++) {
                read.run();
            }
        } finally {
            stop.set(true);
            writer.join(5_000L);
        }
        if (writerFailure.get() != null) {
            throw new AssertionError("the background indexer failed", writerFailure.get());
        }
    }

    private static <T> void churn(String dbName, String collName, String fieldName, Class<T> indexType,
            AtomicBoolean stop, AtomicReference<Throwable> failure) {
        var generation = 0;
        while (!stop.get()) {
            try {
                rl.lockIndex(dbName, collName, fieldName);
                try {
                    final var entries = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, indexType);
                    if (entries != null) {
                        for (final var entry : entries) {
                            entry.getIds().add("churn-" + generation);
                            entry.getIds().remove("churn-" + (generation - 1));
                        }
                        generation++;
                    }
                } finally {
                    rl.releaseIndex(dbName, collName, fieldName);
                }
            } catch (Exception e) {
                failure.compareAndSet(null, e);
                return;
            }
        }
    }
}
