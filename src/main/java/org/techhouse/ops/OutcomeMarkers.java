package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ioc.IocContainer;

public final class OutcomeMarkers {
    static final int READ_CHUNK = 1024;
    static final String SUFFIX = Globals.COLL_IDENTIFIER_SEPARATOR + AdminTransactionEntry.MARKER_OUTCOME;
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Map<String, Known> known = new ConcurrentHashMap<>();

    private record Known(PkIndexEntry row, long resolvedAt) {
    }

    private OutcomeMarkers() {
    }

    public static void written(List<AdminTransactionEntry> inserts) {
        for (final var insert : inserts) {
            final var markerId = insert.get_id();
            if (!markerId.endsWith(SUFFIX)) {
                continue;
            }
            final var row = cache.getPkIndexTransaction(markerId);
            if (row != null) {
                known.put(markerId, new Known(row, System.currentTimeMillis()));
            }
        }
    }

    public static int rebuild() throws IOException, InterruptedException {
        resolvedBefore(Long.MIN_VALUE);
        return known.size();
    }

    public static List<String> resolvedBefore(long cutoff) throws IOException, InterruptedException {
        final var rows = cache.getTransactionPkIndexes();
        final var expired = new ArrayList<String>();
        final var unknown = new ArrayList<String>();
        var markers = 0;
        for (final var row : rows.entrySet()) {
            if (!row.getKey().endsWith(SUFFIX)) {
                continue;
            }
            markers++;
            final var held = known.get(row.getKey());
            if (held == null || held.row() != row.getValue()) {
                unknown.add(row.getKey());
            } else if (held.resolvedAt() < cutoff) {
                expired.add(row.getKey());
            }
        }
        for (var from = 0; from < unknown.size(); from += READ_CHUNK) {
            for (final var learned : readChunk(unknown.subList(from, Math.min(unknown.size(), from + READ_CHUNK)))
                    .entrySet()) {
                if (learned.getValue() < cutoff) {
                    expired.add(learned.getKey());
                }
            }
        }
        if (known.size() > markers) {
            known.keySet().removeIf(markerId -> !rows.containsKey(markerId));
        }
        return expired;
    }

    public static String dtxIdOf(String markerId) {
        return markerId.substring(0, markerId.length() - SUFFIX.length());
    }

    private static Map<String, Long> readChunk(List<String> markerIds) throws IOException, InterruptedException {
        final var before = new HashMap<String, PkIndexEntry>(markerIds.size() * 2);
        for (final var markerId : markerIds) {
            final var row = cache.getPkIndexTransaction(markerId);
            if (row != null) {
                before.put(markerId, row);
            }
        }
        final var learned = new HashMap<String, Long>(markerIds.size() * 2);
        for (final var marker : AdminOperationHelper.readTransactionOps(markerIds)) {
            final var row = before.get(marker.get_id());
            if (row == null) {
                continue;
            }
            final var resolvedAt = Tx2pcLog.resolvedAtOf(marker);
            known.compute(marker.get_id(),
                    (_, held) -> held != null && cache.getPkIndexTransaction(marker.get_id()) == held.row()
                            ? held
                            : new Known(row, resolvedAt));
            learned.put(marker.get_id(), resolvedAt);
        }
        return learned;
    }
}
