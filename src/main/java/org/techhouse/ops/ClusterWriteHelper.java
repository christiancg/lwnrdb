package org.techhouse.ops;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.WriteGuard;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;

public final class ClusterWriteHelper {
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);

    private ClusterWriteHelper() {
    }

    public static OperationResponse guard(OperationType type, String dbName, String collName) {
        return errorFor(type, coordinator.guardWrite(dbName, collName));
    }

    private static OperationResponse errorFor(OperationType type, WriteGuard guard) {
        return switch (guard.kind()) {
            case ALLOW -> null;
            case NO_QUORUM -> new OperationResponse(type, ErrorCode.NO_QUORUM);
            case NOT_OWNER -> guard.ownerAddress() != null
                    ? new OperationResponse(type, ErrorCode.NOT_COLLECTION_OWNER, "owner=" + guard.ownerAddress())
                    : new OperationResponse(type, ErrorCode.NOT_COLLECTION_OWNER);
        };
    }

    public static OperationResponse afterSave(String dbName, String collName, OperationResponse response) {
        if (response instanceof SaveResponse saveResponse) {
            return withReplication(response, OperationType.SAVE,
                    coordinator.replicateUpsert(dbName, collName, List.of(saveResponse.get_id())));
        }
        return response;
    }

    public static OperationResponse afterBulkSave(String dbName, String collName, OperationResponse response) {
        if (response instanceof BulkSaveResponse bulkSaveResponse) {
            final var ids = new ArrayList<>(bulkSaveResponse.getInserted());
            ids.addAll(bulkSaveResponse.getUpdated());
            return withReplication(response, OperationType.BULK_SAVE,
                    coordinator.replicateUpsert(dbName, collName, ids));
        }
        return response;
    }

    public static Long reserveDelete(String dbName, String collName, String id) throws java.io.IOException {
        return coordinator.reserveDelete(dbName, collName, List.of(id));
    }

    public static OperationResponse afterDelete(String dbName, String collName, String id, Long reservedVersion,
            OperationResponse response) {
        if (response instanceof DeleteResponse) {
            return withReplication(response, OperationType.DELETE,
                    coordinator.replicateDelete(dbName, collName, List.of(id), reservedVersion));
        }
        return response;
    }

    private static OperationResponse withReplication(OperationResponse success, OperationType type,
            ReplicationOutcome outcome) {
        return switch (outcome) {
            case TIMEOUT -> new OperationResponse(type, ErrorCode.REPLICATION_TIMEOUT);
            case NOT_OWNER -> new OperationResponse(type, ErrorCode.NOT_COLLECTION_OWNER);
            case NOT_CLUSTERED, QUORUM_MET -> success;
        };
    }

    public static OperationResponse stillOwnsOrError(OperationType type, String dbName, String collName) {
        return coordinator.stillOwns(dbName, collName)
                ? null
                : new OperationResponse(type, ErrorCode.NOT_COLLECTION_OWNER);
    }
}
