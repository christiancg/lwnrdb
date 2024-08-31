package org.techhouse.ops;

import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.*;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.*;
import org.techhouse.utils.JsonUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AggregationOperationHelper {
    private static final String GROUP_FIELD_NAME = "group";
    private static final String COUNT_FIELD_NAME = "count";
    private static final Cache cache = IocContainer.get(Cache.class);

    public static List<JsonObject> processAggregation(AggregateRequest request) throws IOException {
        Stream<JsonObject> resultStream = null;
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        for (var step : request.getAggregationSteps()) {
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

    private static Stream<JsonObject> processFilterStep(BaseAggregationStep baseFilterStep, Stream<JsonObject> resultStream,
                                                        String dbName, String collName)
            throws IOException {
        final var filterStep = (FilterAggregationStep) baseFilterStep;
        final var filterOperator = filterStep.getOperator();
        return FilterOperatorHelper.processOperator(filterOperator, resultStream, dbName, collName);
    }

    private static Stream<JsonObject> processMapStep(BaseAggregationStep baseMapStep, Stream<JsonObject> resultStream,
                                                     String dbName, String collName)
            throws IOException {
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var mapStep = (MapAggregationStep) baseMapStep;
        for(var step : mapStep.getOperators()) {
            resultStream = resultStream.map(jsonObject -> {
                final var copy = jsonObject.deepCopy();
                return MapOperatorHelper.processOperator(step, copy);
            });
        }
        return resultStream;
    }

    private static Stream<JsonObject> processGroupByStep(BaseAggregationStep baseGroupByStep, Stream<JsonObject> resultStream,
                                                         String dbName, String collName)
            throws IOException {
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var groupByStep = (GroupByAggregationStep) baseGroupByStep;
        // TODO: use indexes
        return resultStream.filter(jsonObject -> JsonUtils.hasInPath(jsonObject, groupByStep.getFieldName()))
                .collect(Collectors.groupingBy(jsonObject -> JsonUtils.getFromPath(jsonObject, groupByStep.getFieldName())))
                .entrySet().stream().map(jsonElementListEntry -> {
                    final var groupedEntry = new JsonObject();
                    groupedEntry.add(groupByStep.getFieldName(), jsonElementListEntry.getKey());
                    final var values = jsonElementListEntry.getValue().stream().reduce(new JsonArray(), (jsonArray, jsonObject) -> {
                        jsonArray.add(jsonObject);
                        return jsonArray;
                    }, (jsonArray, jsonArray2) -> jsonArray);
                    groupedEntry.add(GROUP_FIELD_NAME, values);
                    return groupedEntry;
                });
    }

    private static Stream<JsonObject> processJoinStep(BaseAggregationStep baseJoinStep, Stream<JsonObject> resultStream,
                                                      String dbName, String collName)
            throws IOException {
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var joinStep = (JoinAggregationStep) baseJoinStep;
        final var joinCollectionName = joinStep.getJoinCollection();
        final var joinCollectionLocalField = joinStep.getLocalField();
        final var joinCollectionRemoteField = joinStep.getRemoteField();
        final var as = joinStep.getAsField();
        final var joinCollectionMap = cache.getWholeCollection(dbName, joinCollectionName);
        // TODO: use indexes
        final var joinedCollection = joinCollectionMap.values().stream()
                .map(DbEntry::getData)
                .filter(jsonObject -> JsonUtils.hasInPath(jsonObject, joinCollectionRemoteField))
                .collect(Collectors.groupingBy(jsonObject -> JsonUtils.getFromPath(jsonObject, joinCollectionRemoteField),
                        HashMap::new, Collectors.collectingAndThen(Collectors.toList(), jsonObjects -> {
                            final var jsonArray = new JsonArray();
                            jsonObjects.forEach(jsonArray::add);
                            return jsonArray;
                        })));
        return resultStream.map(jsonObject -> {
            if (JsonUtils.hasInPath(jsonObject, joinCollectionLocalField)) {
                final var copy = jsonObject.deepCopy();
                final var found = joinedCollection.get(JsonUtils.getFromPath(copy, joinCollectionLocalField));
                copy.add(as, found);
                return copy;
            }
            return jsonObject;
        });
    }

    private static Stream<JsonObject> processCountStep(Stream<JsonObject> resultStream, String dbName, String collName) {
        final var result = new JsonObject();
        if (resultStream != null) {
            result.addProperty(COUNT_FIELD_NAME, resultStream.count());
        } else {
            final var adminCollEntry = cache.getAdminCollectionEntry(dbName, collName);
            result.addProperty(COUNT_FIELD_NAME, adminCollEntry.getEntryCount());
        }
        return Stream.of(result);
    }

    private static Stream<JsonObject> processDistinctStep(BaseAggregationStep baseDistinctStep, Stream<JsonObject> resultStream,
                                                          String dbName, String collName)
            throws IOException {
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var distinctStep = (DistinctAggregationStep) baseDistinctStep;
        final var fieldName = distinctStep.getFieldName();
        if (fieldName == null || fieldName.isEmpty() || fieldName.trim().isEmpty()) {
            return resultStream.map(jsonObject -> {
                final var result = jsonObject.deepCopy();
                if (result.has(Globals.PK_FIELD)) {
                    result.remove(Globals.PK_FIELD);
                }
                return result;
            }).distinct();
        } else {
            // TODO: use indexes if fieldName has an index
            return resultStream.filter(jsonObject -> JsonUtils.hasInPath(jsonObject, fieldName)).map(jsonObject -> {
                final var json = new JsonObject();
                json.add(fieldName, JsonUtils.getFromPath(jsonObject, fieldName));
                return json;
            }).distinct();
        }
    }

    private static Stream<JsonObject> processLimitStep(BaseAggregationStep baseLimitStep, Stream<JsonObject> resultStream,
                                                       String dbName, String collName)
            throws IOException {
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var limitStep = (LimitAggregationStep) baseLimitStep;
        return resultStream.limit(limitStep.getLimit());
    }

    private static Stream<JsonObject> processSkipStep(BaseAggregationStep baseSkipStep, Stream<JsonObject> resultStream,
                                                      String dbName, String collName)
            throws IOException {
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var skipStep = (SkipAggregationStep) baseSkipStep;
        return resultStream.skip(skipStep.getSkip());
    }

    private static Stream<JsonObject> processSortStep(BaseAggregationStep baseSortStep, Stream<JsonObject> resultStream,
                                                      String dbName, String collName)
            throws IOException {
        resultStream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var sortStep = (SortAggregationStep) baseSortStep;
        // TODO: use indexes
        if (sortStep.getAscending()) {
            return resultStream.sorted((o1, o2) -> JsonUtils.sortFunctionAscending(o1,o2, sortStep.getFieldName()));
        } else {
            return resultStream.sorted((o1, o2) -> JsonUtils.sortFunctionDescending(o1,o2, sortStep.getFieldName()));
        }
    }
}
