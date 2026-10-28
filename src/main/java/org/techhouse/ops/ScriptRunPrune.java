package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.OperationResponse;

public final class ScriptRunPrune {
    static final int ROWS_PER_CHUNK = 1024;
    private static final String STARTED_AT = "startedAt";
    private static final Comparator<Row> OLDEST_FIRST = Comparator.comparingLong(Row::startedAt).thenComparing(Row::id);
    private static final Comparator<Row> PAGE_ORDER = Comparator.comparingLong(Row::page)
            .thenComparingLong(Row::startedAt).thenComparing(Row::id);

    private static final Logger logger = Logger.logFor(ScriptRunPrune.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static ClusterCoordinator clusterCoordinator = IocContainer.get(ClusterCoordinator.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);

    private record Row(String id, long page, long startedAt) {
    }

    private ScriptRunPrune() {
    }

    public static int prune(String dbName, long cutoff, long maxRows) throws Exception {
        if (cache.getAdminCollectionEntry(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME) == null) {
            return 0;
        }
        final var selected = selectRows(dbName, cutoff, maxRows);
        selected.sort(PAGE_ORDER);
        var deleted = 0;
        for (var from = 0; from < selected.size(); from += ROWS_PER_CHUNK) {
            final var ids = new ArrayList<String>(Math.min(ROWS_PER_CHUNK, selected.size() - from));
            for (final var row : selected.subList(from, Math.min(selected.size(), from + ROWS_PER_CHUNK))) {
                ids.add(row.id());
            }
            final var chunk = new int[1];
            final var response = OperationLocks.withCollectionLock(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME,
                    OperationType.DELETE, ErrorCode.ERROR_DELETING, () -> deleteChunk(dbName, ids, chunk));
            deleted += chunk[0];
            if (response.getStatus() != OperationStatus.OK) {
                logger.warning("Stopped pruning the script run history of '" + dbName + "' after " + deleted
                        + " row(s): " + response.getMessage());
                break;
            }
        }
        if (deleted > 0) {
            logger.info("Pruned " + deleted + " script run history row(s) from '" + dbName + "'");
        }
        return deleted;
    }

    private static List<Row> selectRows(String dbName, long cutoff, long maxRows) throws Exception {
        final var stale = new ArrayList<Row>();
        final var kept = new ArrayList<Row>();
        try (var entries = cache.streamCollection(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME)) {
            entries.forEach(entry -> {
                final var data = entry.getData();
                final var startedAt = data != null && data.has(STARTED_AT)
                        ? data.get(STARTED_AT).asJsonNumber().getValue().longValue()
                        : Long.MIN_VALUE;
                final var row = new Row(entry.get_id(), entry.getPage(), startedAt);
                (startedAt != Long.MIN_VALUE && startedAt < cutoff ? stale : kept).add(row);
            });
        }
        if (maxRows > 0 && kept.size() > maxRows) {
            kept.sort(OLDEST_FIRST);
            stale.addAll(kept.subList(0, (int) (kept.size() - maxRows)));
        }
        return stale;
    }

    private static OperationResponse deleteChunk(String dbName, List<String> ids, int[] deleted) throws Exception {
        final var collName = Globals.SCRIPT_RUNS_COLLECTION_NAME;
        final var refusal = ClusterWriteHelper.refusalUnderLock(OperationType.DELETE, dbName, collName);
        if (refusal != null) {
            return refusal;
        }
        final var reservation = clusterCoordinator.reserveDelete(dbName, collName, ids);
        if (reservation.ownerLost()) {
            return new OperationResponse(OperationType.DELETE, ErrorCode.NOT_COLLECTION_OWNER);
        }
        final List<String> removed;
        try {
            removed = DeleteOperationHelper.executeBulkDelete(dbName, collName, ids, listenManager, true);
        } catch (Exception e) {
            clusterCoordinator.retractDelete(dbName, collName, ids, reservation.version());
            throw e;
        }
        deleted[0] = removed.size();
        if (!removed.isEmpty()) {
            clusterCoordinator.replicateDelete(dbName, collName, removed, reservation.version());
        }
        return new DeleteResponse("Pruned " + removed.size() + " row(s)");
    }
}
