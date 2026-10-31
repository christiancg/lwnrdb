package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.utils.FieldPath;
import org.techhouse.utils.SaltedKey;

final class GroupByOperatorHelper {
    private static final String GROUP_FIELD_NAME = "group";
    private static final Comparator<IdKeyed> BY_ID = Comparator.comparing(IdKeyed::id);
    private static final Cache cache = IocContainer.get(Cache.class);

    private GroupByOperatorHelper() {
    }

    private record IdKeyed(String id, JsonObject document) {
    }

    static Stream<JsonObject> processGroupByStep(GroupByAggregationStep groupByStep, Stream<JsonObject> resultStream,
            String dbName, String collName, PipelineContext context) throws IOException {
        final var fieldName = groupByStep.getFieldName();
        if (resultStream == null && !ScanPreference.indexFetchNotWorthIt(dbName, collName)) {
            final var indexEntries = IndexHelper.getIndexEntriesForField(dbName, collName, fieldName, context);
            if (indexEntries != null) {
                return groupByViaIndex(indexEntries, dbName, collName, fieldName, context);
            }
        }
        final var path = FieldPath.of(fieldName);
        final Map<SaltedKey, List<JsonObject>> grouped;
        try (var documents = context.source(resultStream, dbName, collName)) {
            grouped = documents.filter(path::isIn)
                    .collect(Collectors.groupingBy(jsonObject -> new SaltedKey(path.valueIn(jsonObject))));
        }
        return grouped.entrySet().stream().map(jsonElementListEntry -> {
            final var groupedEntry = new JsonObject();
            path.set(groupedEntry, jsonElementListEntry.getKey().element());
            groupedEntry.add(GROUP_FIELD_NAME, inIdOrder(jsonElementListEntry.getValue(), context));
            return groupedEntry;
        });
    }

    static JsonArray inIdOrder(List<JsonObject> documents, PipelineContext context) {
        context.tick(documents.size());
        final var keyed = new IdKeyed[documents.size()];
        for (var i = 0; i < keyed.length; i++) {
            final var document = documents.get(i);
            keyed[i] = new IdKeyed(documentId(document), document);
        }
        Arrays.sort(keyed, BY_ID);
        final var array = new JsonArray();
        for (final var row : keyed) {
            array.add(row.document());
        }
        return array;
    }

    private static String documentId(JsonObject document) {
        final var id = document.get(Globals.PK_FIELD);
        return id instanceof JsonString jsonString ? jsonString.getValue() : "";
    }

    private static Stream<JsonObject> groupByViaIndex(List<FieldIndexEntry<?>> indexEntries, String dbName,
            String collName, String fieldName, PipelineContext context) throws IOException {
        final var allIds = new HashSet<String>();
        for (var indexEntry : indexEntries) {
            context.tick(indexEntry.getIds().size());
            allIds.addAll(indexEntry.getIds());
        }
        final var docById = new HashMap<String, JsonObject>();
        final var fetched = cache.getEntriesByIds(dbName, collName, allIds);
        context.scanned(fetched.size());
        for (var dbEntry : fetched) {
            docById.put(dbEntry.get_id(), dbEntry.getData());
        }
        final var path = FieldPath.of(fieldName);
        final var grouped = new ArrayList<JsonObject>();
        for (var indexEntry : indexEntries) {
            context.tick(indexEntry.getIds().size());
            final var groupDocuments = new ArrayList<JsonObject>();
            for (var id : indexEntry.getIds()) {
                final var doc = docById.get(id);
                if (doc != null) {
                    groupDocuments.add(doc);
                }
            }
            if (groupDocuments.isEmpty()) {
                continue;
            }
            final var groupedEntry = new JsonObject();
            path.set(groupedEntry, IndexHelper.indexValueToElement(indexEntry.getValue()));
            groupedEntry.add(GROUP_FIELD_NAME, inIdOrder(groupDocuments, context));
            grouped.add(groupedEntry);
        }
        return grouped.stream();
    }
}
