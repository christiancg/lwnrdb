package org.techhouse.cluster;

import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;

final class AdminSnapshotKeys {
    private AdminSnapshotKeys() {
    }

    static String schema(String dbName, String collName) {
        return of("schema", dbName, collName);
    }

    static String triggers(String dbName, String collName) {
        return of("triggers", dbName, collName);
    }

    static String procedure(String dbName, String name) {
        return of("procedure", dbName, name);
    }

    static String schedule(String dbName, String name) {
        return of("schedule", dbName, name);
    }

    static String procedures(String dbName) {
        return of("procedures", dbName, Globals.PROCEDURES_FOLDER);
    }

    static String schedules(String dbName) {
        return of("schedules", dbName, Globals.SCHEDULES_FOLDER);
    }

    private static String of(String kind, String dbName, String name) {
        return kind + Globals.COLL_IDENTIFIER_SEPARATOR + Cache.getCollectionIdentifier(dbName, name);
    }
}
