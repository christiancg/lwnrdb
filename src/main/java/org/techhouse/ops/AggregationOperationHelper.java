package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
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
        var startIndex = 0;
        if (resultStream == null) {
            final var fastCount = CountOperatorHelper.tryIndexOnlyCount(steps, dbName, collName);
            if (fastCount != null) {
                resultStream = Stream.of(fastCount.result());
                // The steps up to and including the COUNT have been answered from the indexes; any
                // steps after the COUNT still run normally on the produced {count:N} stream.
                startIndex = fastCount.nextStepIndex();
            }
        }
        for (var i = startIndex; i < steps.size(); i++) {
            final var step = steps.get(i);
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
        try {
            return resultStream != null ? resultStream.toList() : new ArrayList<>();
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static Stream<JsonObject> processCountStep(Stream<JsonObject> resultStream, String dbName,
            String collName) {
        return CountOperatorHelper.processCountStep(resultStream, dbName, collName);
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
    // When the remote field is indexed, getMatchingIdsForJoin does one binary-search-backed lookup
    // per distinct local value instead of deep-copying every index entry and doing a linear scan.
    // Falls back to the whole-collection group when no index exists on the remote field.
    private static Map<JsonBaseElement, JsonArray> buildJoinLookup(String dbName, String joinCollectionName,
            String remoteField, List<JsonObject> leftEntries, String localField) throws IOException {
        final var localValues = new HashSet<JsonBaseElement>();
        for (var left : leftEntries) {
            if (JsonUtils.hasInPath(left, localField)) {
                localValues.add(JsonUtils.getFromPath(left, localField));
            }
        }
        final var matchingIds = IndexHelper.getMatchingIdsForJoin(dbName, joinCollectionName, remoteField, localValues);
        if (matchingIds == null) {
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
                return sortViaIndex(indexEntries, dbName, collName, ascending);
            }
        }
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        if (ascending) {
            return resultStream.sorted((o1, o2) -> JsonUtils.sortFunctionAscending(o1, o2, fieldName));
        } else {
            return resultStream.sorted((o1, o2) -> JsonUtils.sortFunctionDescending(o1, o2, fieldName));
        }
    }

    // Index-backed SORT: index entries are sorted with an allocation-free comparator (no JsonObject
    // wrapper per comparison), then documents are fetched lazily one-by-one so a downstream LIMIT
    // only triggers as many reads as it needs (e.g. SORT+LIMIT 10 fetches only 10 docs).
    private static Stream<JsonObject> sortViaIndex(List<FieldIndexEntry<?>> indexEntries, String dbName,
            String collName, boolean ascending) {
        final var sortedEntries = new ArrayList<>(indexEntries);
        sortedEntries.sort((a, b) -> compareIndexValues(a.getValue(), b.getValue(), ascending));
        final var orderedIds = new ArrayList<String>();
        for (var entry : sortedEntries) {
            orderedIds.addAll(entry.getIds());
        }
        return orderedIds.stream().map(id -> {
            try {
                final var docs = cache.getEntriesByIds(dbName, collName, Set.of(id));
                return docs.isEmpty() ? null : docs.getFirst().getData();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).filter(Objects::nonNull);
    }

    // Allocation-free comparator for raw FieldIndexEntry values. Handles all stored value kinds
    // (Number, Boolean, String, JsonCustom, JsonNull, JsonBaseElement) without creating a JsonObject
    // wrapper per comparison, matching the sort semantics of sortFunctionAscending/Descending.
    private static int compareIndexValues(Object a, Object b, boolean ascending) {
        if (!ascending) {
            return compareIndexValues(b, a, true);
        }
        final var aIsNull = (a == null || a instanceof JsonNull);
        final var bIsNull = (b == null || b instanceof JsonNull);
        if (aIsNull && bIsNull) {
            return 0;
        }
        if (aIsNull) {
            return 1; // nulls sort last
        }
        if (bIsNull) {
            return -1;
        }
        switch (a) {
            case Number na when b instanceof Number nb -> {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            case String sa when b instanceof String sb -> {
                return sa.compareTo(sb);
            }
            case Boolean ba -> {
                return ba ? -1 : 1;
            }
            case JsonCustom<?> ca when b instanceof JsonCustom<?> cb
                    && ca.getClass().isAssignableFrom(cb.getClass()) -> {
                return ca.getValue().compareTo(cb.getValue());
            }
            default -> {
            }
        }
        final var elemA = IndexHelper.indexValueToElement(a);
        final var elemB = IndexHelper.indexValueToElement(b);
        if (elemA.isJsonPrimitive() && !elemB.isJsonPrimitive()) {
            return 1;
        }
        return -1;
    }
}
