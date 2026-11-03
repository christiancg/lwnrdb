package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
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
            String dbName, String collName, boolean onlyCounted, PipelineContext context) throws IOException {
        final var fieldName = groupByStep.getFieldName();
        final var path = FieldPath.of(fieldName);
        if (resultStream == null && (onlyCounted || !ScanPreference.groupByPrefersScan(dbName, collName))) {
            final var indexEntries = IndexHelper.getIndexEntriesForField(dbName, collName, fieldName, context);
            if (indexEntries != null) {
                return onlyCounted
                        ? indexedGroupKeys(indexEntries, path)
                        : groupByViaIndex(indexEntries, dbName, collName, path, context);
            }
        }
        if (onlyCounted) {
            return scannedGroupKeys(context.source(resultStream, dbName, collName), path, context);
        }
        final var grouped = new HashMap<SaltedKey, List<JsonObject>>();
        final var held = new HeldRows(context);
        try (var documents = context.source(resultStream, dbName, collName)) {
            documents.filter(path::isIn).forEach(document -> {
                held.hold(document);
                grouped.computeIfAbsent(new SaltedKey(path.valueIn(document)), _ -> new ArrayList<>()).add(document);
            });
        }
        return grouped.entrySet().stream().map(jsonElementListEntry -> {
            final var groupedEntry = new JsonObject();
            path.set(groupedEntry, jsonElementListEntry.getKey().element());
            groupedEntry.add(GROUP_FIELD_NAME, inIdOrder(jsonElementListEntry.getValue(), context));
            return groupedEntry;
        });
    }

    private static Stream<JsonObject> indexedGroupKeys(List<FieldIndexEntry<?>> indexEntries, FieldPath path) {
        return indexEntries.stream().filter(entry -> !entry.getIds().isEmpty())
                .map(entry -> keyRow(path, IndexHelper.indexValueToElement(entry.getValue())));
    }

    private static Stream<JsonObject> scannedGroupKeys(Stream<JsonObject> source, FieldPath path,
            PipelineContext context) {
        final var keys = new HashSet<SaltedKey>();
        final var held = new HeldRows(context);
        try (var documents = source) {
            documents.filter(path::isIn).forEach(document -> {
                final var key = path.valueIn(document);
                held.keptWhen(keys.add(new SaltedKey(key)), key);
            });
        }
        return keys.stream().map(key -> keyRow(path, key.element()));
    }

    private static JsonObject keyRow(FieldPath path, JsonBaseElement key) {
        final var row = new JsonObject();
        path.set(row, key);
        return row;
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
            String collName, FieldPath path, PipelineContext context) throws IOException {
        final var allIds = new HashSet<String>();
        for (var indexEntry : indexEntries) {
            context.tick(indexEntry.getIds().size());
            allIds.addAll(indexEntry.getIds());
        }
        final var docById = new HashMap<String, JsonObject>();
        final var fetched = cache.getEntriesByIds(dbName, collName, allIds);
        context.scanned(fetched.size());
        final var held = new HeldRows(context);
        for (var dbEntry : fetched) {
            held.hold(dbEntry.getData());
            docById.put(dbEntry.get_id(), dbEntry.getData());
        }
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
            final var groupedEntry = keyRow(path, IndexHelper.indexValueToElement(indexEntry.getValue()));
            groupedEntry.add(GROUP_FIELD_NAME, inIdOrder(groupDocuments, context));
            grouped.add(groupedEntry);
        }
        return grouped.stream();
    }
}
