package org.techhouse.ops.admin;

import java.util.UUID;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ResultHasher;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.CollectionAccessHelper;
import org.techhouse.ops.CollectionReadinessGuard;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationLocks;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.StopListenRequest;
import org.techhouse.ops.resp.ListenResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.StopListenResponse;

public final class ListenOperationHelper {
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final EJson eJson = IocContainer.get(EJson.class);

    private ListenOperationHelper() {
    }

    public static OperationResponse processListenOperation(ListenRequest listenRequest, UUID clientId) {
        if (!listenManager.hasCapacityFor(clientId)) {
            return new OperationResponse(OperationType.LISTEN, ErrorCode.TOO_MANY_LISTENS);
        }
        final var username = clientTracker.getAuthenticatedUsername(clientId);
        if (!listenManager.hasUserCapacity(username)) {
            return new OperationResponse(OperationType.LISTEN, ErrorCode.TOO_MANY_LISTENS_FOR_USER);
        }
        final var retainedBytes = retainedBytesOf(listenRequest);
        final var refusal = listenManager.reserve(username, retainedBytes);
        if (refusal != null) {
            return new OperationResponse(OperationType.LISTEN, refusal);
        }
        OperationResponse response = null;
        try {
            response = registerReserved(listenRequest, clientId, username, retainedBytes);
            return response;
        } finally {
            if (!(response instanceof ListenResponse)) {
                listenManager.release(username, retainedBytes);
            }
        }
    }

    private static long retainedBytesOf(ListenRequest listenRequest) {
        return eJson.toJson(listenRequest).length();
    }

    private static OperationResponse registerReserved(ListenRequest listenRequest, UUID clientId, String username,
            long retainedBytes) {
        final var dbName = listenRequest.getDatabaseName();
        final var collName = listenRequest.getCollectionName();
        final var aggReq = new AggregateRequest(dbName, collName);
        aggReq.setAggregationSteps(listenRequest.getAggregationSteps());
        final var notRegistered = CollectionReadinessGuard.precheckRead(OperationType.LISTEN, dbName,
                AggregationOperationHelper.aggregateCollections(aggReq));
        if (notRegistered != null) {
            return notRegistered;
        }
        return OperationLocks.withReadLocks(false, AggregationOperationHelper.aggregateLockSet(aggReq),
                OperationType.LISTEN, ErrorCode.ERROR_LISTEN, () -> {
                    final var unregistered = CollectionReadinessGuard.checkRead(OperationType.LISTEN, dbName,
                            AggregationOperationHelper.aggregateCollections(aggReq));
                    if (unregistered != null) {
                        return unregistered;
                    }
                    final var results = AggregationOperationHelper.processAggregation(aggReq);
                    final var ordered = ResultHasher.ordersResults(listenRequest.getAggregationSteps());
                    final var initialHash = ResultHasher.hash(results, ordered);
                    final var dirtyReq = new AggregateRequest(dbName, collName);
                    dirtyReq.setAggregationSteps(listenRequest.getAggregationSteps());
                    dirtyReq.setDirtyRead(true);
                    CollectionAccessHelper.recordCollectionAccess(dbName, collName);
                    final var listenId = listenManager.register(clientId, username, dirtyReq, initialHash,
                            retainedBytes);
                    return new ListenResponse(listenId.toString(), results, initialHash, false);
                });
    }

    public static OperationResponse processStopListenOperation(StopListenRequest request, UUID clientId) {
        return OperationResponse.respondOrError(OperationType.STOP_LISTEN, ErrorCode.ERROR_LISTEN, () -> {
            final var listenId = java.util.UUID.fromString(request.getListenId());
            final var unregistered = listenManager.unregister(listenId, clientId);
            if (!unregistered) {
                return new OperationResponse(OperationType.STOP_LISTEN, ErrorCode.LISTEN_NOT_FOUND);
            }
            return new StopListenResponse();
        });
    }
}
