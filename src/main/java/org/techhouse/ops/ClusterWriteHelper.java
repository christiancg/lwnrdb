package org.techhouse.ops;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;

/**
 * Bridges the write handlers to the clustering layer: rejects writes this node may not coordinate, and after
 * a successful local commit replicates them to the quorum. All methods are no-ops when clustering is off or
 * this node is not the owner, so the single-node write path is unchanged.
 */
public final class ClusterWriteHelper {
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);

    private ClusterWriteHelper() {
    }

    // Returns an error response if this node may not coordinate the write, or null to proceed.
    public static OperationResponse guard(OperationType type, String dbName, String collName) {
        final var guard = coordinator.guardWrite(dbName, collName);
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

    public static OperationResponse afterDelete(String dbName, String collName, String id, OperationResponse response) {
        if (response instanceof DeleteResponse) {
            return withReplication(response, OperationType.DELETE,
                    coordinator.replicateDelete(dbName, collName, List.of(id)));
        }
        return response;
    }

    private static OperationResponse withReplication(OperationResponse success, OperationType type,
            ReplicationOutcome outcome) {
        return outcome == ReplicationOutcome.TIMEOUT
                ? new OperationResponse(type, ErrorCode.REPLICATION_TIMEOUT)
                : success;
    }
}
