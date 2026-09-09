package org.techhouse.ops.admin;

import java.util.List;
import java.util.UUID;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ResultHasher;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.CollectionAccessHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.StopListenRequest;
import org.techhouse.ops.resp.ListenResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.StopListenResponse;

// LISTEN / STOP_LISTEN registration.
public final class ListenOperationHelper {
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);

    private ListenOperationHelper() {
    }

    public static OperationResponse processListenOperation(ListenRequest listenRequest, UUID clientId) {
        List<String> readLocks = List.of();
        try {
            final var dbName = listenRequest.getDatabaseName();
            final var collName = listenRequest.getCollectionName();
            // Build an AggregateRequest so we can use the existing aggregation infrastructure.
            final var aggReq = new AggregateRequest(dbName, collName);
            aggReq.setAggregationSteps(listenRequest.getAggregationSteps());
            readLocks = locks.acquireReadLocks(false, AggregationOperationHelper.aggregateLockSet(aggReq));
            final var results = AggregationOperationHelper.processAggregation(aggReq);
            final var initialHash = ResultHasher.hash(results);
            // The re-run request uses dirty reads: timeliness matters more than strict consistency
            // for push notifications, and per-file locks still ensure valid data.
            final var dirtyReq = new AggregateRequest(dbName, collName);
            dirtyReq.setAggregationSteps(listenRequest.getAggregationSteps());
            dirtyReq.setDirtyRead(true);
            final var listenId = listenManager.register(clientId, dirtyReq, initialHash);
            CollectionAccessHelper.recordCollectionAccess(dbName, collName);
            return new ListenResponse(listenId.toString(), results, initialHash, false);
        } catch (Exception e) {
            return new OperationResponse(OperationType.LISTEN, ErrorCode.ERROR_LISTEN);
        } finally {
            locks.releaseReadLocks(readLocks);
        }
    }

    public static OperationResponse processStopListenOperation(StopListenRequest request) {
        try {
            final var listenId = java.util.UUID.fromString(request.getListenId());
            final var unregistered = listenManager.unregister(listenId);
            if (!unregistered) {
                return new OperationResponse(OperationType.STOP_LISTEN, ErrorCode.LISTEN_NOT_FOUND);
            }
            return new StopListenResponse();
        } catch (Exception e) {
            return new OperationResponse(OperationType.STOP_LISTEN, ErrorCode.ERROR_LISTEN);
        }
    }
}
