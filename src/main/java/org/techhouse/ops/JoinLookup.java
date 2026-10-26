package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.cache.Cache;
import org.techhouse.data.DbEntry;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.PrimaryKeyIndexResolver;
import org.techhouse.utils.JsonUtils;
import org.techhouse.utils.SaltedKey;

final class JoinLookup {
    private static final Cache cache = IocContainer.get(Cache.class);

    private JoinLookup() {
    }

    static Map<SaltedKey, JsonArray> build(String dbName, String joinCollectionName, String remoteField,
            List<JsonObject> leftEntries, String localField, Transaction transaction, PipelineContext context)
            throws IOException {
        final var localValues = new SaltedElementSet();
        for (var left : leftEntries) {
            final var localValue = JsonUtils.resolvePath(left, localField);
            if (localValue != null) {
                localValues.add(localValue);
            }
        }
        final var joinCollId = Cache.getCollectionIdentifier(dbName, joinCollectionName);
        final var overlay = transaction != null ? transaction.overlayFor(joinCollId) : null;
        if (overlay != null && !overlay.isEmpty()) {
            return groupByRemoteField(TransactionOperationHelper.applyOverlayToStream(transaction, joinCollId,
                    context.source(null, dbName, joinCollectionName)), remoteField, context);
        }
        if (JsonUtils.isPrimaryKeyPath(remoteField)) {
            final var matchingIds = PrimaryKeyIndexResolver.existingIds(localValues, dbName, joinCollectionName,
                    context);
            return lookupFromMatches(fetchMatches(dbName, joinCollectionName, matchingIds, context), remoteField);
        }
        final var matchingIds = IndexHelper.getMatchingIdsForJoin(dbName, joinCollectionName, remoteField, localValues,
                context);
        if (matchingIds == null) {
            final var joinCollectionMap = cache.getWholeCollection(dbName, joinCollectionName);
            context.scanned(joinCollectionMap.size());
            return groupByRemoteField(joinCollectionMap.values().stream().map(DbEntry::getData), remoteField, context);
        }
        return lookupFromMatches(fetchMatches(dbName, joinCollectionName, matchingIds, context), remoteField);
    }

    private static List<DbEntry> fetchMatches(String dbName, String joinCollectionName, Set<String> matchingIds,
            PipelineContext context) throws IOException {
        if (matchingIds.isEmpty()) {
            return List.of();
        }
        final var matchedDocs = cache.getEntriesByIds(dbName, joinCollectionName, matchingIds);
        context.scanned(matchedDocs.size());
        return matchedDocs;
    }

    private static Map<SaltedKey, JsonArray> lookupFromMatches(List<DbEntry> matchedDocs, String remoteField) {
        final var matchedByKey = new HashMap<SaltedKey, List<JsonObject>>();
        for (var dbEntry : matchedDocs) {
            final var data = dbEntry.getData();
            final var key = JsonUtils.resolvePath(data, remoteField);
            if (key != null) {
                matchedByKey.computeIfAbsent(new SaltedKey(key), _ -> new ArrayList<>()).add(data);
            }
        }
        final var lookup = new HashMap<SaltedKey, JsonArray>();
        matchedByKey.forEach((key, documents) -> lookup.put(key, AggregationOperationHelper.inIdOrder(documents)));
        return lookup;
    }

    private static Map<SaltedKey, JsonArray> groupByRemoteField(Stream<JsonObject> documents, String remoteField,
            PipelineContext context) {
        try (var remoteDocuments = context.watch(documents)) {
            return remoteDocuments.filter(jsonObject -> JsonUtils.hasInPath(jsonObject, remoteField))
                    .collect(Collectors.groupingBy(
                            jsonObject -> new SaltedKey(JsonUtils.getFromPath(jsonObject, remoteField)), HashMap::new,
                            Collectors.collectingAndThen(Collectors.toList(), AggregationOperationHelper::inIdOrder)));
        }
    }
}
