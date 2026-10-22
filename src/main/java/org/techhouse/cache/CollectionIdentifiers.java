package org.techhouse.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.config.Globals;

final class CollectionIdentifiers {
    static final int MAX_REMEMBERED = 8192;
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> BY_DATABASE = new ConcurrentHashMap<>();
    private static final AtomicInteger remembered = new AtomicInteger();

    private CollectionIdentifiers() {
    }

    static String of(String dbName, String collName) {
        if (dbName == null || collName == null) {
            return concatenated(dbName, collName);
        }
        final var collections = BY_DATABASE.get(dbName);
        final var hit = collections == null ? null : collections.get(collName);
        return hit != null ? hit : remember(dbName, collName);
    }

    static void forget(String dbName, String collName) {
        final var collections = BY_DATABASE.get(dbName);
        if (collections != null && collections.remove(collName) != null) {
            remembered.decrementAndGet();
        }
    }

    static void forgetDatabase(String dbName) {
        final var collections = BY_DATABASE.remove(dbName);
        if (collections != null) {
            remembered.addAndGet(-collections.size());
        }
    }

    private static String remember(String dbName, String collName) {
        final var identifier = concatenated(dbName, collName);
        if (remembered.get() >= MAX_REMEMBERED) {
            return identifier;
        }
        final var collections = BY_DATABASE.computeIfAbsent(dbName, _ -> new ConcurrentHashMap<>());
        final var previous = collections.putIfAbsent(collName, identifier);
        if (previous != null) {
            return previous;
        }
        remembered.incrementAndGet();
        return identifier;
    }

    private static String concatenated(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }
}
