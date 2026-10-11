package org.techhouse.ops.admin;

import java.util.List;
import java.util.Locale;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.PermissionsRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SetPasswordRequest;

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

    public static List<AdminRecordKey> touchedBy(OperationRequest request) {
        final var dbName = request.getDatabaseName();
        return switch (request) {
            case SaveProcedureRequest save -> List.of(procedure(dbName, save.getName()));
            case DeleteProcedureRequest delete -> List.of(procedure(dbName, delete.getName()));
            case SaveScheduleRequest save -> List.of(schedule(dbName, save.getName()));
            case DeleteScheduleRequest delete -> List.of(schedule(dbName, delete.getName()));
            case PermissionsRequest permissions -> List.of(user(permissions.getUsername()));
            case DeleteUserRequest delete -> List.of(user(delete.getUsername()));
            case SetPasswordRequest password -> List.of(user(password.getUsername()));
            default -> touchedByType(request);
        };
    }

    private static List<AdminRecordKey> touchedByType(OperationRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        return switch (request.getType()) {
            case CREATE_DATABASE, DROP_DATABASE, SET_DATABASE_OWNERS -> List.of(database(dbName));
            case CREATE_COLLECTION, DROP_COLLECTION, CREATE_INDEX, DROP_INDEX -> List.of(collection(dbName, collName));
            case SAVE_SCHEMA, DELETE_SCHEMA -> List.of(schema(dbName, collName));
            case SAVE_TRIGGER, DELETE_TRIGGER -> List.of(triggers(dbName, collName));
            default -> List.of();
        };
    }
}
