package org.techhouse.ops.admin;

import java.util.Locale;

public record AdminRecordKey(Kind kind, String dbName, String name) {
    private static final String SEPARATOR = "|";

    public enum Kind {
        DATABASE, COLLECTION, USER, SCHEMA, TRIGGERS, PROCEDURE, SCHEDULE;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static AdminRecordKey database(String dbName) {
        return new AdminRecordKey(Kind.DATABASE, dbName, dbName);
    }

    public static AdminRecordKey collection(String dbName, String collName) {
        return new AdminRecordKey(Kind.COLLECTION, dbName, collName);
    }

    public static AdminRecordKey user(String username) {
        return new AdminRecordKey(Kind.USER, "", username);
    }

    public static AdminRecordKey schema(String dbName, String collName) {
        return new AdminRecordKey(Kind.SCHEMA, dbName, collName);
    }

    public static AdminRecordKey triggers(String dbName, String collName) {
        return new AdminRecordKey(Kind.TRIGGERS, dbName, collName);
    }

    public static AdminRecordKey procedure(String dbName, String name) {
        return new AdminRecordKey(Kind.PROCEDURE, dbName, name);
    }

    public static AdminRecordKey schedule(String dbName, String name) {
        return new AdminRecordKey(Kind.SCHEDULE, dbName, name);
    }

    public String id() {
        return kind.label() + SEPARATOR + dbName + SEPARATOR + name;
    }

    public static AdminRecordKey parse(String id) {
        final var parts = id.split("\\|", 3);
        return new AdminRecordKey(Kind.valueOf(parts[0].toUpperCase(Locale.ROOT)), parts[1], parts[2]);
    }
}
