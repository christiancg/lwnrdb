package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.custom_types.GeoDistanceComparator;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.utils.GeoPoint;
import org.techhouse.utils.GeoUtils;

// The geohash-sorted index yields candidate ids only; the caller (FilterOperatorHelper) re-tests each
// fetched document exactly, so the pre-filter can never change the result - only reduce document reads.
public final class GeoSpatialIndexHelper {
    private GeoSpatialIndexHelper() {
    }

    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    public static Set<String> candidateIds(CustomOperator operator, String dbName, String collName) throws IOException {
        final var bbox = boundingBoxFor(operator);
        if (bbox == null) {
            return null;
        }
        if (cache.hasNoIndex(dbName, collName, operator.getField())) {
            return null;
        }
        final var fieldName = operator.getField();
        final List<FieldIndexEntry<JsonGeo>> entries;
        try {
            rl.lockIndexRead(dbName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            entries = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, JsonGeo.class);
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
        final var candidates = collectByBoundingBox(entries, bbox);
        // Not-yet-indexed committed writes are added as candidates; the caller re-tests exactly.
        candidates.addAll(pendingIndexWrites.idsFor(dbName, collName));
        return candidates;
    }

    private static GeoUtils.BoundingBox boundingBoxFor(CustomOperator operator) {
        final var name = operator.getCustomOperatorName();
        if (JsonGeo.OPERATOR_WITHIN.equals(name)) {
            final var polygonArray = operator.getArgs().get("polygon").asJsonArray();
            final var polygon = new ArrayList<GeoPoint>();
            for (var vertex : polygonArray.asList()) {
                polygon.add(((JsonGeo) vertex).point());
            }
            return GeoUtils.boundingBoxOf(polygon);
        }
        if (JsonGeo.OPERATOR_DISTANCE.equals(name)) {
            final var comparator = GeoDistanceComparator
                    .valueOf(operator.getArgs().get("comparator").asJsonString().getValue());
            // Only "within radius" is a contiguous range; "farther than" selects outside the box, so scan.
            if (comparator != GeoDistanceComparator.SMALLER_THAN
                    && comparator != GeoDistanceComparator.SMALLER_THAN_EQUALS) {
                return null;
            }
            final var target = ((JsonGeo) operator.getValue()).point();
            final var distance = operator.getArgs().get("distance").asJsonNumber().getValue().doubleValue();
            return GeoUtils.boundingBoxForRadius(target, distance);
        }
        return null;
    }

    private static Set<String> collectByBoundingBox(List<FieldIndexEntry<JsonGeo>> entries, GeoUtils.BoundingBox bbox) {
        final var ids = new HashSet<String>();
        for (var prefix : GeoUtils.coveringGeohashPrefixes(bbox)) {
            var i = lowerBound(entries, prefix);
            while (i < entries.size() && entries.get(i).getValue().geoHash().startsWith(prefix)) {
                final var geo = entries.get(i).getValue();
                if (bbox.contains(geo.point())) {
                    ids.addAll(entries.get(i).getIds());
                }
                i++;
            }
        }
        return ids;
    }

    private static int lowerBound(List<FieldIndexEntry<JsonGeo>> entries, String prefix) {
        var lo = 0;
        var hi = entries.size();
        while (lo < hi) {
            final var mid = (lo + hi) >>> 1;
            if (entries.get(mid).getValue().geoHash().compareTo(prefix) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static void recordAnalyzeIndexUse(String dbName, String collName, String fieldName) {
        final var analyzeContext = AnalyzeContext.current();
        if (analyzeContext != null) {
            analyzeContext.addIndexUsed(fieldName);
            analyzeContext.addLock(AnalyzeContext.fieldLockId(dbName, collName, fieldName));
        }
    }
}
