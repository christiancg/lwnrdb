package org.techhouse.ops;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.utils.VectorUtils;

// Approximate (ANN) candidate pre-filter for the vector "nearest" operator: it scans the neighbourhood
// of the query's SimHash signature in the signature-sorted index and returns candidate ids, which the
// caller re-scores exactly. Being locality-sensitive but not exact, it may miss matches outside the
// scanned neighbourhood; an "exact" operator (candidateIds returns null) forces a full scan instead.
public final class VectorSimilarityIndexHelper {
    private VectorSimilarityIndexHelper() {
    }

    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    // Gather this many candidates per K (with a floor) so the exact re-score has enough to choose from.
    private static final int CANDIDATE_MULTIPLIER = 10;
    private static final int MIN_CANDIDATES = 100;

    // Null when not index-accelerable (not nearest, an exact query, or no index) so the caller scans.
    public static Set<String> candidateIds(CustomOperator operator, String dbName, String collName) throws IOException {
        if (!JsonVector.OPERATOR_NEAREST.equals(operator.getCustomOperatorName()) || isExact(operator)) {
            return null;
        }
        if (cache.hasNoIndex(dbName, collName, operator.getField())) {
            return null;
        }
        final var fieldName = operator.getField();
        final List<FieldIndexEntry<JsonVector>> entries;
        try {
            rl.lockIndexRead(dbName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            entries = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, JsonVector.class);
        } finally {
            rl.releaseIndexRead(dbName, collName, fieldName);
        }
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        recordAnalyzeIndexUse(dbName, collName, fieldName);
        if (!Globals.ADMIN_DB_NAME.equals(dbName)) {
            cache.recordFieldIndexAccess(dbName, collName, fieldName);
        }
        final var query = JsonVector.toVector(operator.getValue());
        final var budget = Math.max(kFor(operator) * CANDIDATE_MULTIPLIER, MIN_CANDIDATES);
        final var candidates = collectNeighbourhood(entries, query, budget);
        // Not-yet-indexed committed writes may be missing from the index; the caller re-scores them exactly.
        candidates.addAll(pendingIndexWrites.idsFor(dbName, collName));
        return candidates;
    }

    // Expands outward from the query's insertion point until `budget` ids are gathered.
    private static Set<String> collectNeighbourhood(List<FieldIndexEntry<JsonVector>> entries, double[] query,
            int budget) {
        final var querySignature = VectorUtils.simHash(query, JsonVector.SIMHASH_BITS);
        final var start = lowerBound(entries, querySignature);
        final var ids = new HashSet<String>();
        var lo = start - 1;
        var hi = start;
        while (ids.size() < budget && (lo >= 0 || hi < entries.size())) {
            if (hi < entries.size()) {
                ids.addAll(entries.get(hi).getIds());
                hi++;
            }
            if (ids.size() >= budget) {
                break;
            }
            if (lo >= 0) {
                ids.addAll(entries.get(lo).getIds());
                lo--;
            }
        }
        return ids;
    }

    // First entry whose signature is >= the query signature (binary search over the sorted list).
    private static int lowerBound(List<FieldIndexEntry<JsonVector>> entries, String signature) {
        var lo = 0;
        var hi = entries.size();
        while (lo < hi) {
            final var mid = (lo + hi) >>> 1;
            if (entries.get(mid).getValue().simHash().compareTo(signature) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static boolean isExact(CustomOperator operator) {
        final var exact = operator.getArgs().get("exact");
        return exact != null && exact.isJsonBoolean() && exact.asJsonBoolean().getValue();
    }

    private static int kFor(CustomOperator operator) {
        return operator.getArgs().get("k").asJsonNumber().getValue().intValue();
    }

    private static void recordAnalyzeIndexUse(String dbName, String collName, String fieldName) {
        final var analyzeContext = AnalyzeContext.current();
        if (analyzeContext != null) {
            analyzeContext.addIndexUsed(fieldName);
            analyzeContext.addLock(AnalyzeContext.fieldLockId(dbName, collName, fieldName));
        }
    }
}
