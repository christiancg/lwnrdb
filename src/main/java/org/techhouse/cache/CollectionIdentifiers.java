package org.techhouse.cache;

import org.techhouse.config.Globals;
import org.techhouse.data.NameMemo;

public final class CollectionIdentifiers {
    static final int MAX_REMEMBERED = 8192;
    private static final NameMemo<CollectionNames> MEMO = new NameMemo<>(MAX_REMEMBERED);

    private CollectionIdentifiers() {
    }

    static String of(String dbName, String collName) {
        return namesOf(dbName, collName).identifier();
    }

    static CollectionNames namesOf(String dbName, String collName) {
        final var hit = MEMO.get(dbName, collName);
        if (hit != null) {
            return hit;
        }
        final var names = new CollectionNames(dbName, collName, concatenated(dbName, collName));
        return dbName == null || collName == null ? names : MEMO.remember(dbName, collName, names);
    }

    public static void forget(String dbName, String collName) {
        MEMO.forget(dbName, collName);
    }

    static void forgetDatabase(String dbName) {
        MEMO.forgetAll(dbName);
    }

    private static String concatenated(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }
}
