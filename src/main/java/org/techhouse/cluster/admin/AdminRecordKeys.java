package org.techhouse.cluster.admin;

import java.util.List;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.PermissionsRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SetPasswordRequest;

public final class AdminRecordKeys {
    private AdminRecordKeys() {
    }

    public static List<AdminRecordKey> touchedBy(OperationRequest request) {
        final var dbName = request.getDatabaseName();
        return switch (request) {
            case SaveProcedureRequest save -> List.of(AdminRecordKey.procedure(dbName, save.getName()));
            case DeleteProcedureRequest delete -> List.of(AdminRecordKey.procedure(dbName, delete.getName()));
            case SaveScheduleRequest save -> List.of(AdminRecordKey.schedule(dbName, save.getName()));
            case DeleteScheduleRequest delete -> List.of(AdminRecordKey.schedule(dbName, delete.getName()));
            case PermissionsRequest permissions -> List.of(AdminRecordKey.user(permissions.getUsername()));
            case DeleteUserRequest delete -> List.of(AdminRecordKey.user(delete.getUsername()));
            case SetPasswordRequest password -> List.of(AdminRecordKey.user(password.getUsername()));
            default -> touchedByType(request);
        };
    }

    private static List<AdminRecordKey> touchedByType(OperationRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        return switch (request.getType()) {
            case CREATE_DATABASE, DROP_DATABASE, SET_DATABASE_OWNERS -> List.of(AdminRecordKey.database(dbName));
            case CREATE_COLLECTION, DROP_COLLECTION, CREATE_INDEX, DROP_INDEX ->
                List.of(AdminRecordKey.collection(dbName, collName));
            case SAVE_SCHEMA, DELETE_SCHEMA -> List.of(AdminRecordKey.schema(dbName, collName));
            case SAVE_TRIGGER, DELETE_TRIGGER -> List.of(AdminRecordKey.triggers(dbName, collName));
            default -> List.of();
        };
    }
}
