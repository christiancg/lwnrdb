package org.techhouse.ops;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ClusterCoordinator.DeleteReservation;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.WriteGuard;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;

public final class ClusterWriteHelper {
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);

    private ClusterWriteHelper() {
    }

    public static OperationResponse guard(OperationType type, String dbName, String collName) {
        final var refusal = errorFor(type, coordinator.guardWrite(dbName, collName));
        return refusal != null ? refusal : CollectionReadinessGuard.precheck(type, dbName, collName);
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

    public static DeleteReservation reserveDelete(String dbName, String collName, String id)
            throws java.io.IOException {
        return coordinator.reserveDelete(dbName, collName, List.of(id));
    }

    public static OperationResponse deleteOrRetract(DeleteRequest request, Long reservedVersion) throws Exception {
        final OperationResponse local;
        try {
            local = DeleteOperationHelper.executeDelete(request);
        } catch (Exception e) {
            retractDelete(request, reservedVersion);
            throw e;
        }
        if (!(local instanceof DeleteResponse)) {
            retractDelete(request, reservedVersion);
        }
        return local;
    }

    private static void retractDelete(DeleteRequest request, Long reservedVersion) throws java.io.IOException {
        coordinator.retractDelete(request.getDatabaseName(), request.getCollectionName(), List.of(request.get_id()),
                reservedVersion);
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
            case NOT_OWNER, NOT_COORDINATOR -> new OperationResponse(type, ErrorCode.WRITE_OUTCOME_UNKNOWN);
            case NOT_CLUSTERED, QUORUM_MET -> success;
        };
    }

    public static OperationResponse refusalUnderLock(OperationType type, String dbName, String collName) {
        if (!coordinator.stillOwns(dbName, collName)) {
            return new OperationResponse(type, ErrorCode.NOT_COLLECTION_OWNER);
        }
        faultInjector.at(FaultPoint.WRITE_OWNERSHIP_RECHECKED, Cache.getCollectionIdentifier(dbName, collName));
        return CollectionReadinessGuard.check(type, dbName, collName);
    }
}
