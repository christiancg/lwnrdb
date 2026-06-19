package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.utils.JsonUtils;

public final class AggregationOperationHelper {
    private AggregationOperationHelper() {
    }
    private static final String GROUP_FIELD_NAME = "group";
    private static final String COUNT_FIELD_NAME = "count";
    private static final Cache cache = IocContainer.get(Cache.class);

    public static List<JsonObject> processStepsOnStream(List<BaseAggregationStep> steps,
            Stream<JsonObject> initialStream) throws IOException {
        return applySteps(steps, initialStream, "", "");
    }

    public static List<JsonObject> processAggregation(AggregateRequest request) throws IOException {
        return applySteps(request.getAggregationSteps(), null, request.getDatabaseName(), request.getCollectionName());
    }

    private static List<JsonObject> applySteps(List<BaseAggregationStep> steps, Stream<JsonObject> initialStream,
            String dbName, String collName) throws IOException {
        Stream<JsonObject> resultStream = initialStream;
        for (var step : steps) {
            resultStream = switch (step.getType()) {
                case FILTER -> processFilterStep(step, resultStream, dbName, collName);
                case MAP -> processMapStep(step, resultStream, dbName, collName);
                case GROUP_BY -> processGroupByStep(step, resultStream, dbName, collName);
                case JOIN -> processJoinStep(step, resultStream, dbName, collName);
                case COUNT -> processCountStep(resultStream, dbName, collName);
                case DISTINCT -> processDistinctStep(step, resultStream, dbName, collName);
                case LIMIT -> processLimitStep(step, resultStream, dbName, collName);
                case SKIP -> processSkipStep(step, resultStream, dbName, collName);
                case SORT -> processSortStep(step, resultStream, dbName, collName);
            };
        }
        return resultStream != null ? resultStream.toList() : new ArrayList<>();
    }

    private static Stream<JsonObject> processFilterStep(BaseAggregationStep baseFilterStep,
            Stream<JsonObject> resultStream, String dbName, String collName) throws IOException {
        final var filterStep = (FilterAggregationStep) baseFilterStep;
        final var filterOperator = filterStep.getOperator();
        return FilterOperatorHelper.processOperator(filterOperator, resultStream, dbName, collName);
    }

    private static Stream<JsonObject> processMapStep(BaseAggregationStep baseMapStep, Stream<JsonObject> resultStream,
            String dbName, String collName) throws IOException {
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var mapStep = (MapAggregationStep) baseMapStep;
        for (var step : mapStep.getOperators()) {
            resultStream = resultStream.map(jsonObject -> {
                final var copy = jsonObject.deepCopy();
                return MapOperatorHelper.processOperator(step, copy);
            });
        }
        return resultStream;
    }

    private static Stream<JsonObject> processGroupByStep(BaseAggregationStep baseGroupByStep,
            Stream<JsonObject> resultStream, String dbName, String collName) throws IOException {
        final var groupByStep = (GroupByAggregationStep) baseGroupByStep;
        final var fieldName = groupByStep.getFieldName();
        if (resultStream == null) {
            final var indexEntries = IndexHelper.getIndexEntriesForField(dbName, collName, fieldName);
            if (indexEntries != null) {
                return groupByViaIndex(indexEntries, dbName, collName, fieldName);
            }
        }
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        return resultStream.filter(jsonObject -> JsonUtils.hasInPath(jsonObject, groupByStep.getFieldName()))
                .collect(Collectors
                        .groupingBy(jsonObject -> JsonUtils.getFromPath(jsonObject, groupByStep.getFieldName())))
                .entrySet().stream().map(jsonElementListEntry -> {
                    final var groupedEntry = new JsonObject();
                    groupedEntry.add(groupByStep.getFieldName(), jsonElementListEntry.getKey());
                    final var values = jsonElementListEntry.getValue().stream().reduce(new JsonArray(),
                            (jsonArray, jsonObject) -> {
                                jsonArray.add(jsonObject);
                                return jsonArray;
                            }, (jsonArray, _) -> jsonArray);
                    groupedEntry.add(GROUP_FIELD_NAME, values);
                    return groupedEntry;
                });
    }

    // Index-backed GROUP_BY: the field index already maps each value to the set of matching ids, so
    // we fetch each group's documents by id (positioned reads) instead of scanning and grouping the
    // whole collection in memory.
    private static Stream<JsonObject> groupByViaIndex(List<FieldIndexEntry<?>> indexEntries, String dbName,
            String collName, String fieldName) throws IOException {
        final var grouped = new ArrayList<JsonObject>();
        for (var indexEntry : indexEntries) {
            final var docs = cache.getEntriesByIds(dbName, collName, indexEntry.getIds());
            if (docs.isEmpty()) {
                continue;
            }
            final var values = new JsonArray();
            docs.forEach(dbEntry -> values.add(dbEntry.getData()));
            final var groupedEntry = new JsonObject();
            groupedEntry.add(fieldName, IndexHelper.indexValueToElement(indexEntry.getValue()));
            groupedEntry.add(GROUP_FIELD_NAME, values);
            grouped.add(groupedEntry);
        }
        return grouped.stream();
    }

    private static Stream<JsonObject> processJoinStep(BaseAggregationStep baseJoinStep, Stream<JsonObject> resultStream,
            String dbName, String collName) throws IOException {
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var joinStep = (JoinAggregationStep) baseJoinStep;
        final var joinCollectionName = joinStep.getJoinCollection();
        final var joinCollectionLocalField = joinStep.getLocalField();
        final var joinCollectionRemoteField = joinStep.getRemoteField();
        final var as = joinStep.getAsField();
        // Blocking step (documented exception): JOIN groups the remote side in memory before the
        // per-row attach, so the left side is materialized here to drive the remote lookup.
        final var leftEntries = resultStream.toList();
        final var joinedCollection = buildJoinLookup(dbName, joinCollectionName, joinCollectionRemoteField, leftEntries,
                joinCollectionLocalField);
        return leftEntries.stream().map(jsonObject -> {
            if (JsonUtils.hasInPath(jsonObject, joinCollectionLocalField)) {
                final var copy = jsonObject.deepCopy();
                final var found = joinedCollection.get(JsonUtils.getFromPath(copy, joinCollectionLocalField));
                copy.add(as, found);
                return copy;
            }
            return jsonObject;
        });
    }

    // Builds the remote-side lookup (remote field value -> matching remote documents) for a JOIN.
    // When the remote field is indexed, only the remote documents whose value matches a local value
    // actually present on the left side are fetched (positioned reads); otherwise the whole remote
    // collection is grouped in memory (previous behavior).
    private static Map<JsonBaseElement, JsonArray> buildJoinLookup(String dbName, String joinCollectionName,
            String remoteField, List<JsonObject> leftEntries, String localField) throws IOException {
        final var indexEntries = IndexHelper.getIndexEntriesForField(dbName, joinCollectionName, remoteField);
        if (indexEntries == null) {
            final var joinCollectionMap = cache.getWholeCollection(dbName, joinCollectionName);
            return joinCollectionMap.values().stream().map(DbEntry::getData)
                    .filter(jsonObject -> JsonUtils.hasInPath(jsonObject, remoteField))
                    .collect(Collectors.groupingBy(jsonObject -> JsonUtils.getFromPath(jsonObject, remoteField),
                            HashMap::new, Collectors.collectingAndThen(Collectors.toList(), jsonObjects -> {
                                final var jsonArray = new JsonArray();
                                jsonObjects.forEach(jsonArray::add);
                                return jsonArray;
                            })));
        }
        final var localValues = new HashSet<JsonBaseElement>();
        for (var left : leftEntries) {
            if (JsonUtils.hasInPath(left, localField)) {
                localValues.add(JsonUtils.getFromPath(left, localField));
            }
        }
        final var matchingIds = new HashSet<String>();
        for (var indexEntry : indexEntries) {
            if (localValues.contains(IndexHelper.indexValueToElement(indexEntry.getValue()))) {
                matchingIds.addAll(indexEntry.getIds());
            }
        }
        final var matchedDocs = cache.getEntriesByIds(dbName, joinCollectionName, matchingIds);
        final var lookup = new HashMap<JsonBaseElement, JsonArray>();
        for (var dbEntry : matchedDocs) {
            final var data = dbEntry.getData();
            if (JsonUtils.hasInPath(data, remoteField)) {
                final var key = JsonUtils.getFromPath(data, remoteField);
                lookup.computeIfAbsent(key, _ -> new JsonArray()).add(data);
            }
        }
        return lookup;
    }

    private static Stream<JsonObject> processCountStep(Stream<JsonObject> resultStream, String dbName,
            String collName) {
        final var result = new JsonObject();
        if (resultStream != null) {
            result.addProperty(COUNT_FIELD_NAME, resultStream.count());
        } else {
            final var adminPageEntries = cache.getAdminPageEntries(dbName, collName);
            final var totalCount = adminPageEntries != null
                    ? adminPageEntries.stream().mapToInt(AdminPageEntry::getEntryCount).sum()
                    : 0;
            result.addProperty(COUNT_FIELD_NAME, totalCount);
        }
        return Stream.of(result);
    }

    private static Stream<JsonObject> processDistinctStep(BaseAggregationStep baseDistinctStep,
            Stream<JsonObject> resultStream, String dbName, String collName) throws IOException {
        final var distinctStep = (DistinctAggregationStep) baseDistinctStep;
        final var fieldName = distinctStep.getFieldName();
        if (resultStream == null && fieldName != null && !fieldName.isBlank()) {
            final var indexEntries = IndexHelper.getIndexEntriesForField(dbName, collName, fieldName);
            if (indexEntries != null) {
                // The index keys are exactly the distinct values for the field, so no documents
                // are read at all. The trailing distinct() is a cheap safety net.
                return indexEntries.stream().map(indexEntry -> {
                    final var json = new JsonObject();
                    json.add(fieldName, IndexHelper.indexValueToElement(indexEntry.getValue()));
                    return json;
                }).distinct();
            }
        }
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        if (fieldName == null || fieldName.isBlank()) {
            return resultStream.map(jsonObject -> {
                final var result = jsonObject.deepCopy();
                if (result.has(Globals.PK_FIELD)) {
                    result.remove(Globals.PK_FIELD);
                }
                return result;
            }).distinct();
        } else {
            return resultStream.filter(jsonObject -> JsonUtils.hasInPath(jsonObject, fieldName)).map(jsonObject -> {
                final var json = new JsonObject();
                json.add(fieldName, JsonUtils.getFromPath(jsonObject, fieldName));
                return json;
            }).distinct();
        }
    }

    private static Stream<JsonObject> processLimitStep(BaseAggregationStep baseLimitStep,
            Stream<JsonObject> resultStream, String dbName, String collName) throws IOException {
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var limitStep = (LimitAggregationStep) baseLimitStep;
        return resultStream.limit(limitStep.getLimit());
    }

    private static Stream<JsonObject> processSkipStep(BaseAggregationStep baseSkipStep, Stream<JsonObject> resultStream,
            String dbName, String collName) throws IOException {
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var skipStep = (SkipAggregationStep) baseSkipStep;
        return resultStream.skip(skipStep.getSkip());
    }

    private static Stream<JsonObject> processSortStep(BaseAggregationStep baseSortStep, Stream<JsonObject> resultStream,
            String dbName, String collName) throws IOException {
        final var sortStep = (SortAggregationStep) baseSortStep;
        final var fieldName = sortStep.getFieldName();
        final var ascending = sortStep.getAscending();
        if (resultStream == null) {
            final var indexEntries = IndexHelper.getIndexEntriesForField(dbName, collName, fieldName);
            if (indexEntries != null) {
                return sortViaIndex(indexEntries, dbName, collName, fieldName, ascending);
            }
        }
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        if (ascending) {
            return resultStream.sorted((o1, o2) -> JsonUtils.sortFunctionAscending(o1, o2, fieldName));
        } else {
            return resultStream.sorted((o1, o2) -> JsonUtils.sortFunctionDescending(o1, o2, fieldName));
        }
    }

    // Index-backed SORT: the field index entries are ordered with the same comparator used by the
    // scan path (applied to a single-field wrapper object), then documents are fetched and emitted
    // in that order. Tie order within a value is arbitrary, matching the unindexed stream order.
    private static Stream<JsonObject> sortViaIndex(List<FieldIndexEntry<?>> indexEntries, String dbName,
            String collName, String fieldName, boolean ascending) throws IOException {
        final var sortedEntries = new ArrayList<>(indexEntries);
        sortedEntries.sort((a, b) -> {
            final var o1 = new JsonObject();
            o1.add(fieldName, IndexHelper.indexValueToElement(a.getValue()));
            final var o2 = new JsonObject();
            o2.add(fieldName, IndexHelper.indexValueToElement(b.getValue()));
            return ascending
                    ? JsonUtils.sortFunctionAscending(o1, o2, fieldName)
                    : JsonUtils.sortFunctionDescending(o1, o2, fieldName);
        });
        final var orderedIds = new ArrayList<String>();
        for (var entry : sortedEntries) {
            orderedIds.addAll(entry.getIds());
        }
        final var docs = cache.getEntriesByIds(dbName, collName, new HashSet<>(orderedIds));
        final var byId = new HashMap<String, JsonObject>();
        for (var doc : docs) {
            byId.put(doc.get_id(), doc.getData());
        }
        return orderedIds.stream().map(byId::get).filter(Objects::nonNull);
    }
}
