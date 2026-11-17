package org.techhouse.ops.snapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationLocks;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.ReadSnapshotRequest;
import org.techhouse.ops.req.ReleaseSnapshotRequest;
import org.techhouse.ops.req.StartSnapshotRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ReadSnapshotResponse;
import org.techhouse.ops.resp.StartSnapshotResponse;

public final class SnapshotOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final SnapshotRegistry registry = IocContainer.get(SnapshotRegistry.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private SnapshotOperationHelper() {
    }

    public static OperationResponse start(StartSnapshotRequest request, UUID clientId, String actingUser) {
        final var dbName = request.getDatabaseName();
        if (cache.getAdminDbEntry(dbName) == null) {
            return new OperationResponse(OperationType.START_SNAPSHOT, "Database " + dbName + " not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        final var collections = readableCollections(dbName, request.getCollectionNames(), actingUser);
        final var lockIds = collections.stream().map(collName -> Cache.getCollectionIdentifier(dbName, collName))
                .toList();
        return fencedWhenRefused(OperationLocks.withReadLocks(false, lockIds, OperationType.START_SNAPSHOT,
                ErrorCode.ERROR_STARTING_SNAPSHOT, true, () -> {
                    final var captured = collections.stream()
                            .filter(collName -> cache.getAdminCollectionEntry(dbName, collName) != null).toList();
                    final var snapshot = registry.open(dbName, captured, clientId);
                    if (snapshot == null) {
                        return new OperationResponse(OperationType.START_SNAPSHOT, ErrorCode.SNAPSHOT_LIMIT_REACHED);
                    }
                    return new StartSnapshotResponse("Ok", snapshot.id(), snapshot.collectionNames());
                }), lockIds);
    }

    public static OperationResponse read(ReadSnapshotRequest request, UUID clientId) {
        final var snapshot = ownedSnapshot(request.getSnapshotId(), request.getDatabaseName(), clientId);
        if (snapshot == null) {
            return new OperationResponse(OperationType.READ_SNAPSHOT, ErrorCode.SNAPSHOT_NOT_FOUND);
        }
        if (snapshot.isAborted()) {
            return aborted(snapshot);
        }
        final var collName = request.getCollectionName();
        if (!snapshot.holds(collName)) {
            return new OperationResponse(OperationType.READ_SNAPSHOT,
                    "Collection " + collName + " is not in snapshot " + snapshot.id(), ErrorCode.VALIDATION_ERROR);
        }
        final var lockIds = List.of(Cache.getCollectionIdentifier(snapshot.dbName(), collName));
        return fencedWhenRefused(OperationLocks.withReadLocks(false, lockIds, OperationType.READ_SNAPSHOT,
                ErrorCode.ERROR_READING_SNAPSHOT, true, () -> readPage(snapshot, request)), lockIds);
    }

    private static OperationResponse fencedWhenRefused(OperationResponse response, List<String> lockIds) {
        if (ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode().equals(response.getErrorCode()) && locks.fences(lockIds)) {
            return new OperationResponse(response.getType(), ErrorCode.COLLECTION_FENCED);
        }
        return response;
    }

    public static OperationResponse release(ReleaseSnapshotRequest request, UUID clientId) {
        final var snapshot = ownedSnapshot(request.getSnapshotId(), request.getDatabaseName(), clientId);
        if (snapshot == null) {
            return new OperationResponse(OperationType.RELEASE_SNAPSHOT, ErrorCode.SNAPSHOT_NOT_FOUND);
        }
        registry.release(snapshot);
        return OperationResponse.ok(OperationType.RELEASE_SNAPSHOT, "Snapshot released");
    }

    private static OperationResponse readPage(Snapshot snapshot, ReadSnapshotRequest request) throws IOException {
        if (snapshot.isAborted()) {
            return aborted(snapshot);
        }
        try {
            final var page = SnapshotReader.read(snapshot, request.getCollectionName(), request.getAfterId(),
                    request.getLimit());
            return new ReadSnapshotResponse("Ok", page.results(), page.lastId(), page.done());
        } catch (IOException e) {
            if (snapshot.isAborted()) {
                return aborted(snapshot);
            }
            throw e;
        }
    }

    private static Snapshot ownedSnapshot(String snapshotId, String dbName, UUID clientId) {
        final var snapshot = registry.find(snapshotId, clientId);
        return snapshot == null || !snapshot.dbName().equals(dbName) ? null : snapshot;
    }

    private static OperationResponse aborted(Snapshot snapshot) {
        return new OperationResponse(OperationType.READ_SNAPSHOT, ErrorCode.SNAPSHOT_ABORTED, snapshot.abortReason());
    }

    private static List<String> readableCollections(String dbName, List<String> requested, String actingUser) {
        final var user = actingUser == null ? null : cache.getAdminUserEntry(actingUser);
        final var names = requested == null ? cache.getCollectionNamesForDatabase(dbName) : requested;
        final var readable = new TreeSet<String>();
        for (final var collName : names) {
            if (AuthorizationChecker.canReadCollection(user, dbName, collName)) {
                readable.add(collName);
            }
        }
        return new ArrayList<>(readable);
    }
}
