package org.techhouse.ops.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ex.CollectionBusyException;
import org.techhouse.ioc.IocContainer;

public final class FieldIndexDropFence {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @FunctionalInterface
    public interface FencedAction<T> {
        T run() throws Exception;
    }

    private record HeldField(String collName, String fieldName) {
    }

    private FieldIndexDropFence() {
    }

    public static <T> T holdingFieldIndexLocks(String dbName, Collection<String> collNames, long timeoutMillis,
            FencedAction<T> action) throws Exception {
        final var held = new ArrayList<HeldField>();
        try {
            lockEveryField(dbName, collNames, timeoutMillis, held);
            return action.run();
        } finally {
            release(dbName, held);
        }
    }

    private static void lockEveryField(String dbName, Collection<String> collNames, long timeoutMillis,
            List<HeldField> held) throws InterruptedException {
        final var deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        for (final var collName : new TreeSet<>(collNames)) {
            for (final var fieldName : new TreeSet<>(cache.getIndexesForCollection(dbName, collName))) {
                final var remaining = Math.max(0L, deadline - System.currentTimeMillis());
                if (!locks.tryLockIndex(dbName, collName, fieldName, remaining)) {
                    throw new CollectionBusyException(Cache.getCollectionIdentifier(dbName, collName)
                            + Globals.COLL_IDENTIFIER_SEPARATOR + fieldName, timeoutMillis);
                }
                held.add(new HeldField(collName, fieldName));
            }
        }
    }

    private static void release(String dbName, List<HeldField> held) {
        for (final var field : held.reversed()) {
            locks.releaseIndex(dbName, field.collName(), field.fieldName());
        }
    }
}
