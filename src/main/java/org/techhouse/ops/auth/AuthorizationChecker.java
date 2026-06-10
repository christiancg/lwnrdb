package org.techhouse.ops.auth;

import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.OperationRequest;

public final class AuthorizationChecker {

    private AuthorizationChecker() {
    }

    public static AuthorizationResult check(OperationRequest req, AdminUserEntry user) {
        if (user == null) {
            return AuthorizationResult.deny("User not found");
        }

        if (user.isAdmin()) {
            return AuthorizationResult.allow();
        }

        final var type = req.getType();

        if (type == OperationType.CREATE_USER || type == OperationType.DELETE_USER || type == OperationType.CHANGE_PERMISSIONS) {
            return AuthorizationResult.deny("action is forbidden, no permissions");
        }

        if (type == OperationType.LIST_DATABASES || type == OperationType.CLOSE_CONNECTION) {
            return AuthorizationResult.allow();
        }

        if (type == OperationType.CREATE_DATABASE) {
            return user.getGlobalPermissions().contains(GlobalPermissionType.CREATE_DATABASE) ?
                    AuthorizationResult.allow() :
                    AuthorizationResult.deny("action is forbidden, no permissions");
        }

        if (type == OperationType.DROP_DATABASE) {
            return user.getGlobalPermissions().contains(GlobalPermissionType.DROP_DATABASE) ?
                    AuthorizationResult.allow() :
                    AuthorizationResult.deny("action is forbidden, no permissions");
        }

        final var dbName = req.getDatabaseName();
        if (dbName == null || dbName.isBlank()) {
            return AuthorizationResult.deny("action is forbidden, no permissions");
        }

        final var requiredLevel = getRequiredPermissionLevel(type);
        final var collName = req.getCollectionName();

        if (collName != null && !collName.isBlank()) {
            final var collKey = dbName + "|" + collName;
            final var collPerm = user.getCollectionPermissions().get(collKey);
            if (collPerm != null && collPerm.covers(requiredLevel)) {
                return AuthorizationResult.allow();
            }
        }

        final var dbPerm = user.getDatabasePermissions().get(dbName);
        if (dbPerm != null && dbPerm.covers(requiredLevel)) {
            return AuthorizationResult.allow();
        }

        return AuthorizationResult.deny("action is forbidden, no permissions");
    }

    private static PermissionLevel getRequiredPermissionLevel(OperationType type) {
        return switch (type) {
            case FIND_BY_ID, AGGREGATE, LIST_COLLECTIONS -> PermissionLevel.READ;
            case SAVE, BULK_SAVE, DELETE, CREATE_COLLECTION, DROP_COLLECTION, CREATE_INDEX, DROP_INDEX -> PermissionLevel.READ_WRITE;
            default -> PermissionLevel.READ_WRITE;
        };
    }
}
