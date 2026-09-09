package org.techhouse.ops.admin;

import java.util.Collections;
import java.util.List;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.Transaction;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.AnalyzeHelper;
import org.techhouse.ops.CollectionAccessHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ScriptOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.simplejs.exceptions.ScriptCallableException;

// FIND_BY_ID and AGGREGATE. Both read under the collection read lock unless the request opted into a
// dirty read, and AGGREGATE additionally owns the analyze context for the whole pipeline.
public final class ReadPathHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private ReadPathHelper() {
    }

    public static OperationResponse processFindByIdOperation(FindByIdRequest findbyIdRequest,
            Transaction activeTransaction) {
        final var dbName = findbyIdRequest.getDatabaseName();
        final var collName = findbyIdRequest.getCollectionName();
        final var id = findbyIdRequest.get_id();
        // Read-your-writes: if the caller's open transaction has buffered a write for this id, serve it
        // (a buffered save returns the buffered document; a buffered delete reads as not-found).
        if (activeTransaction != null) {
            final var overlay = activeTransaction.overlayFor(Cache.getCollectionIdentifier(dbName, collName));
            if (overlay != null && overlay.containsKey(id)) {
                final var buffered = overlay.get(id);
                if (Transaction.isTombstone(buffered)) {
                    return new OperationResponse(OperationType.FIND_BY_ID, ErrorCode.ENTRY_NOT_FOUND);
                }
                return new FindByIdResponse("Ok", buffered);
            }
        }
        List<String> readLocks = List.of();
        try {
            readLocks = locks.acquireReadLocks(findbyIdRequest.isDirtyRead(),
                    List.of(Cache.getCollectionIdentifier(dbName, collName)));
            final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            final var foundIndexEntry = Collections.binarySearch(primaryKeyIndex, id);
            if (foundIndexEntry >= 0) {
                final var primaryKeyIndexEntry = primaryKeyIndex.get(foundIndexEntry);
                final var entry = cache.getById(dbName, collName, primaryKeyIndexEntry);
                CollectionAccessHelper.recordPkIndexAccess(dbName, collName);
                CollectionAccessHelper.recordCollectionAccess(dbName, collName);
                return new FindByIdResponse("Ok", entry.getData());
            } else {
                return new OperationResponse(OperationType.FIND_BY_ID, ErrorCode.ENTRY_NOT_FOUND);
            }
        } catch (Exception exception) {
            return new OperationResponse(OperationType.FIND_BY_ID, ErrorCode.ERROR_RETRIEVING);
        } finally {
            locks.releaseReadLocks(readLocks);
        }
    }

    public static OperationResponse processAggregateOperation(AggregateRequest aggregateRequest,
            Transaction activeTransaction) {
        List<String> readLocks = List.of();
        final var analyzeContext = aggregateRequest.isAnalyze() ? new AnalyzeContext() : null;
        if (analyzeContext != null) {
            AnalyzeContext.set(analyzeContext);
        }
        final var dbName = aggregateRequest.getDatabaseName();
        final var collName = aggregateRequest.getCollectionName();
        final var overlay = activeTransaction != null
                ? activeTransaction.overlayFor(Cache.getCollectionIdentifier(dbName, collName))
                : null;
        try {
            readLocks = locks.acquireReadLocks(aggregateRequest.isDirtyRead(),
                    AggregationOperationHelper.aggregateLockSet(aggregateRequest));
            if (analyzeContext != null) {
                readLocks.forEach(analyzeContext::addLock);
            }
            final List<org.techhouse.ejson.elements.JsonObject> results;
            if (overlay != null && !overlay.isEmpty()) {
                // Read-your-writes: run the pipeline over the committed documents with the transaction's
                // buffered mutations applied at the source. Passing a prepared source stream also disables
                // the index-backed source fast-paths, so the overlaid documents are honoured exactly.
                final var committed = cache.initializeStreamIfNecessary(null, dbName, collName);
                final var source = TransactionOperationHelper.applyOverlayToStream(activeTransaction,
                        Cache.getCollectionIdentifier(dbName, collName), committed);
                results = AggregationOperationHelper.processAggregation(aggregateRequest, source);
            } else {
                results = AggregationOperationHelper.processAggregation(aggregateRequest);
            }
            CollectionAccessHelper.recordCollectionAccess(aggregateRequest.getDatabaseName(),
                    aggregateRequest.getCollectionName());
            if (analyzeContext != null) {
                // In analyze mode the diagnostic is always returned (even with no results), so the empty
                // result returns an AggregateAnalyzeResponse instead of the NO_RESULTS error response.
                return new AggregateAnalyzeResponse("Ok", results,
                        AnalyzeHelper.build(aggregateRequest, analyzeContext));
            }
            return results.isEmpty()
                    ? new OperationResponse(OperationType.AGGREGATE, ErrorCode.NO_RESULTS)
                    : new AggregateResponse("Ok", results);
        } catch (ScriptCallableException scriptFailure) {
            return new OperationResponse(OperationType.AGGREGATE, scriptFailure.getMessage(),
                    ScriptOperationHelper.errorCodeFor(scriptFailure.getErrorName()));
        } catch (Exception e) {
            return new OperationResponse(OperationType.AGGREGATE, ErrorCode.ERROR_AGGREGATING);
        } finally {
            locks.releaseReadLocks(readLocks);
            if (analyzeContext != null) {
                AnalyzeContext.clear();
            }
        }
    }
}
