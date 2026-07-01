package org.techhouse.ops.auth;

import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;

public final class AuthorizationChecker {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Set<OperationType> ADMIN_ONLY_OPERATIONS = Set.of(OperationType.CREATE_USER,
            OperationType.DELETE_USER, OperationType.CHANGE_PERMISSIONS, OperationType.SET_DATABASE_OWNERS,
            OperationType.LIST_USERS, OperationType.GET_DATABASE_STATS);
    private static final Set<OperationType> ALWAYS_ALLOWED_OPERATIONS = Set.of(OperationType.LIST_DATABASES,
            OperationType.CLOSE_CONNECTION, OperationType.SET_PASSWORD, OperationType.STOP_LISTEN);

    private AuthorizationChecker() {
    }

    private static boolean isAdminOnly(OperationType type) {
        return ADMIN_ONLY_OPERATIONS.contains(type);
    }

    public static AuthorizationResult check(OperationRequest req, AdminUserEntry user) {
        if (user == null) {
            return AuthorizationResult.deny("User not found");
        }

        if (user.isAdmin()) {
            return AuthorizationResult.allow();
        }

        final var type = req.getType();

        if (isAdminOnly(type)) {
            return AuthorizationResult.deny("action is forbidden, no permissions");
        }

        if (ALWAYS_ALLOWED_OPERATIONS.contains(type)) {
            return AuthorizationResult.allow();
        }

        if (type == OperationType.CREATE_DATABASE) {
            return user.getGlobalPermissions().contains(GlobalPermissionType.CREATE_DATABASE)
                    ? AuthorizationResult.allow()
                    : AuthorizationResult.deny("action is forbidden, no permissions");
        }

        // DROP_DATABASE requires ownership — global permission alone is not sufficient
        if (type == OperationType.DROP_DATABASE) {
            final var dbEntry = cache.getAdminDbEntry(req.getDatabaseName());
            if (dbEntry != null && dbEntry.isOwner(user.get_id())) {
                return AuthorizationResult.allow();
            }
            return AuthorizationResult.deny("action is forbidden, no permissions");
        }

        final var dbName = req.getDatabaseName();
        if (dbName == null || dbName.isBlank()) {
            return AuthorizationResult.deny("action is forbidden, no permissions");
        }

        // Owners have full access to their database and all its collections
        final var dbEntry = cache.getAdminDbEntry(dbName);
        if (dbEntry != null && dbEntry.isOwner(user.get_id())) {
            return AuthorizationResult.allow();
        }

        final var requiredLevel = getRequiredPermissionLevel(type);
        final var collName = req.getCollectionName();

        if (lacksCollectionAccess(user, dbName, collName, requiredLevel)) {
            return AuthorizationResult.deny("action is forbidden, no permissions");
        }

        // Aggregations and LISTEN may read additional collections through JOIN steps (within the
        // same database). The user must have READ access to every joined collection as well.
        final java.util.List<org.techhouse.ops.req.agg.BaseAggregationStep> stepsToCheck;
        if (req instanceof AggregateRequest aggReq && aggReq.getAggregationSteps() != null) {
            stepsToCheck = aggReq.getAggregationSteps();
        } else if (req instanceof ListenRequest listenReq && listenReq.getAggregationSteps() != null) {
            stepsToCheck = listenReq.getAggregationSteps();
        } else {
            stepsToCheck = java.util.List.of();
        }
        for (final var step : stepsToCheck) {
            if (step instanceof JoinAggregationStep joinStep
                    && lacksCollectionAccess(user, dbName, joinStep.getJoinCollection(), PermissionLevel.READ)) {
                return AuthorizationResult.deny("action is forbidden, no permissions");
            }
        }

        return AuthorizationResult.allow();
    }

    private static boolean lacksCollectionAccess(AdminUserEntry user, String dbName, String collName,
            PermissionLevel requiredLevel) {
        if (collName != null && !collName.isBlank()) {
            final var collPerm = user.getCollectionPermissions().get(dbName + "|" + collName);
            if (collPerm != null && collPerm.covers(requiredLevel)) {
                return false;
            }
        }
        final var dbPerm = user.getDatabasePermissions().get(dbName);
        return dbPerm == null || !dbPerm.covers(requiredLevel);
    }

    private static PermissionLevel getRequiredPermissionLevel(OperationType type) {
        return switch (type) {
            case FIND_BY_ID, AGGREGATE, LIST_COLLECTIONS, LISTEN -> PermissionLevel.READ;
            default -> PermissionLevel.READ_WRITE;
        };
    }
}
