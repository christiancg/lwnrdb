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
import org.techhouse.ops.tx.OverlayRead;
import org.techhouse.utils.FieldPath;
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
        final var localPath = FieldPath.of(localField);
        for (var left : leftEntries) {
            final var localValue = localPath.resolve(left);
            if (localValue != null) {
                localValues.add(localValue);
            }
        }
        final var overlay = transaction != null
                ? transaction.overlayFor(Cache.getCollectionIdentifier(dbName, joinCollectionName))
                : null;
        if (JsonUtils.isPrimaryKeyPath(remoteField)) {
            final var matchingIds = PrimaryKeyIndexResolver.existingIds(localValues, dbName, joinCollectionName,
                    context);
            return lookupFromMatches(
                    OverlayRead.withBuffered(overlay, fetchMatches(dbName, joinCollectionName, matchingIds, context)),
                    remoteField, context);
        }
        final var matchingIds = IndexHelper.getMatchingIdsForJoin(dbName, joinCollectionName, remoteField, localValues,
                context);
        if (matchingIds != null) {
            return lookupFromMatches(
                    OverlayRead.withBuffered(overlay, fetchMatches(dbName, joinCollectionName, matchingIds, context)),
                    remoteField, context);
        }
        if (overlay != null && !overlay.isEmpty()) {
            return groupByRemoteField(
                    OverlayRead.reconcile(overlay, context.source(null, dbName, joinCollectionName), _ -> true),
                    remoteField, context);
        }
        final var joinCollectionMap = cache.getWholeCollection(dbName, joinCollectionName);
        context.scanned(joinCollectionMap.size());
        return groupByRemoteField(joinCollectionMap.values().stream().map(DbEntry::getData), remoteField, context);
    }

    private static List<JsonObject> fetchMatches(String dbName, String joinCollectionName, Set<String> matchingIds,
            PipelineContext context) throws IOException {
        if (matchingIds.isEmpty()) {
            return List.of();
        }
        final var matchedDocs = cache.getEntriesByIds(dbName, joinCollectionName, matchingIds, context::pinnedPkIndex);
        context.scanned(matchedDocs.size());
        final var documents = new ArrayList<JsonObject>(matchedDocs.size());
        for (final var entry : matchedDocs) {
            documents.add(entry.getData());
        }
        return documents;
    }

    private static Map<SaltedKey, JsonArray> lookupFromMatches(List<JsonObject> matchedDocs, String remoteField,
            PipelineContext context) {
        context.tick(matchedDocs.size());
        final var matchedByKey = new HashMap<SaltedKey, List<JsonObject>>();
        final var remotePath = FieldPath.of(remoteField);
        for (var data : matchedDocs) {
            final var key = remotePath.resolve(data);
            if (key != null) {
                matchedByKey.computeIfAbsent(new SaltedKey(key), _ -> new ArrayList<>()).add(data);
            }
        }
        final var lookup = new HashMap<SaltedKey, JsonArray>();
        matchedByKey.forEach((key, documents) -> lookup.put(key, GroupByOperatorHelper.inIdOrder(documents, context)));
        return lookup;
    }

    private static Map<SaltedKey, JsonArray> groupByRemoteField(Stream<JsonObject> documents, String remoteField,
            PipelineContext context) {
        final var remotePath = FieldPath.of(remoteField);
        try (var remoteDocuments = context.watch(documents)) {
            return remoteDocuments.filter(remotePath::isIn)
                    .collect(Collectors.groupingBy(jsonObject -> new SaltedKey(remotePath.valueIn(jsonObject)),
                            HashMap::new, Collectors.collectingAndThen(Collectors.toList(),
                                    rows -> GroupByOperatorHelper.inIdOrder(rows, context))));
        }
    }
}
