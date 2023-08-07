package org.techhouse.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.*;
import org.techhouse.utils.JsonUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AggregationOperationHelper {
    private static final String GROUP_FIELD_NAME = "group";
    private static final String COUNT_FIELD_NAME = "count";
    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    public static List<JsonObject> processAggregation(AggregateRequest request,
                                                      Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                      Map<String, Map<String, DbEntry>> collectionMap) throws ExecutionException, InterruptedException {
        Stream<JsonObject> resultStream = null;
        final var collectionIdentifier = OperationProcessor.getCollectionIdentifier(request.getDatabaseName(), request.getCollectionName());
        for (var step : request.getAggregationSteps()) {
            resultStream = switch (step.getType()) {
                case FILTER -> processFilterStep(step, resultStream, indexMap, collectionMap, collectionIdentifier);
                case MAP -> processMapStep(step, resultStream, collectionMap, collectionIdentifier);
                case GROUP_BY -> processGroupByStep(step, resultStream, collectionMap, collectionIdentifier);
                case JOIN -> processJoinStep(step, resultStream, indexMap, collectionMap, collectionIdentifier);
                case COUNT -> processCountStep(resultStream, collectionMap, collectionIdentifier);
                case DISTINCT -> processDistinctStep(step, resultStream, collectionMap, collectionIdentifier);
                case LIMIT -> processLimitStep(step, resultStream, collectionMap, collectionIdentifier);
                case SKIP -> processSkipStep(step, resultStream, collectionMap, collectionIdentifier);
                case SORT -> processSortStep(step, resultStream, indexMap, collectionMap, collectionIdentifier);
            };
        }
        return resultStream != null ? resultStream.toList() : new ArrayList<>();
    }

    private static Stream<JsonObject> initializeStreamIfNecessary(Stream<JsonObject> resultStream,
                                                                  Map<String, Map<String, DbEntry>> collectionMap,
                                                                  String collectionIdentifier) throws ExecutionException, InterruptedException {
        if (resultStream != null) {
            return resultStream;
        } else {
            var coll = collectionMap.get(collectionIdentifier);
            if (coll == null) {
                coll = fs.readWholeCollection(collectionIdentifier);
                collectionMap.put(collectionIdentifier, coll);
            }
            return coll.values().stream().map(DbEntry::getData);
        }
    }

    private static Stream<JsonObject> processFilterStep(BaseAggregationStep baseFilterStep,
                                                        Stream<JsonObject> resultStream,
                                                        Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                        Map<String, Map<String, DbEntry>> collectionMap,
                                                        String collectionIdentifier) throws ExecutionException, InterruptedException {
        final var filterStep = (FilterAggregationStep) baseFilterStep;
        final var filterOperator = filterStep.getOperator();
        return FilterOperatorHelper.processOperator(filterOperator, resultStream, indexMap, collectionMap,
                collectionIdentifier);
    }

    private static Stream<JsonObject> processMapStep(BaseAggregationStep baseMapStep,
                                                     Stream<JsonObject> resultStream,
                                                     Map<String, Map<String, DbEntry>> collectionMap,
                                                     String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = initializeStreamIfNecessary(resultStream, collectionMap, collectionIdentifier);
        final var mapStep = (MapAggregationStep) baseMapStep;
        for(var step : mapStep.getOperators()) {
            resultStream = resultStream.map(jsonObject -> {
                final var copy = jsonObject.deepCopy();
                return MapOperatorHelper.processOperator(step, copy);
            });
        }
        return resultStream;
    }

    private static Stream<JsonObject> processGroupByStep(BaseAggregationStep baseGroupByStep,
                                                         Stream<JsonObject> resultStream,
                                                         Map<String, Map<String, DbEntry>> collectionMap,
                                                         String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = initializeStreamIfNecessary(resultStream, collectionMap, collectionIdentifier);
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

    private static Stream<JsonObject> processJoinStep(BaseAggregationStep baseJoinStep,
                                                      Stream<JsonObject> resultStream,
                                                      Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                      Map<String, Map<String, DbEntry>> collectionMap,
                                                      String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = initializeStreamIfNecessary(resultStream, collectionMap, collectionIdentifier);
        final var joinStep = (JoinAggregationStep) baseJoinStep;
        final var joinCollectionName = joinStep.getJoinCollection();
        final var joinCollectionLocalField = joinStep.getLocalField();
        final var joinCollectionRemoteField = joinStep.getRemoteField();
        final var as = joinStep.getAsField();
        var joinCollectionMap = collectionMap.get(joinCollectionName);
        if (joinCollectionMap == null) {
            final var parts = collectionIdentifier.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
            final var dbName = parts[0];
            joinCollectionMap = fs.readWholeCollection(dbName, joinCollectionName);
            final var joinCollectionIdentifier = OperationProcessor.getCollectionIdentifier(dbName, joinCollectionName);
            collectionMap.put(joinCollectionIdentifier, joinCollectionMap);
        }
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

    private static Stream<JsonObject> processCountStep(Stream<JsonObject> resultStream,
                                                       Map<String, Map<String, DbEntry>> collectionMap,
                                                       String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = initializeStreamIfNecessary(resultStream, collectionMap, collectionIdentifier);
        final var result = new JsonObject();
        result.addProperty(COUNT_FIELD_NAME, resultStream.count());
        return Stream.of(result);
    }

    private static Stream<JsonObject> processDistinctStep(BaseAggregationStep baseDistinctStep,
                                                          Stream<JsonObject> resultStream,
                                                          Map<String, Map<String, DbEntry>> collectionMap,
                                                          String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = initializeStreamIfNecessary(resultStream, collectionMap, collectionIdentifier);
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
            return resultStream.filter(jsonObject -> JsonUtils.hasInPath(jsonObject, fieldName)).map(jsonObject -> {
                final var json = new JsonObject();
                json.add(fieldName, JsonUtils.getFromPath(jsonObject, fieldName));
                return json;
            }).distinct();
        }
    }

    private static Stream<JsonObject> processLimitStep(BaseAggregationStep baseLimitStep,
                                                       Stream<JsonObject> resultStream,
                                                       Map<String, Map<String, DbEntry>> collectionMap,
                                                       String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = initializeStreamIfNecessary(resultStream, collectionMap, collectionIdentifier);
        final var limitStep = (LimitAggregationStep) baseLimitStep;
        return resultStream.limit(limitStep.getLimit());
    }

    private static Stream<JsonObject> processSkipStep(BaseAggregationStep baseSkipStep,
                                                      Stream<JsonObject> resultStream,
                                                      Map<String, Map<String, DbEntry>> collectionMap,
                                                      String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = initializeStreamIfNecessary(resultStream, collectionMap, collectionIdentifier);
        final var skipStep = (SkipAggregationStep) baseSkipStep;
        return resultStream.skip(skipStep.getSkip());
    }

    private static Stream<JsonObject> processSortStep(BaseAggregationStep baseSortStep,
                                                      Stream<JsonObject> resultStream,
                                                      Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                      Map<String, Map<String, DbEntry>> collectionMap,
                                                      String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = initializeStreamIfNecessary(resultStream, collectionMap, collectionIdentifier);
        final var sortStep = (SortAggregationStep) baseSortStep;
        // TODO: use indexes
        if (sortStep.isAscending()) {
            return resultStream.sorted((o1, o2) -> JsonUtils.sortFunctionAscending(o1,o2, sortStep.getFieldName()));
        } else {
            return resultStream.sorted((o1, o2) -> JsonUtils.sortFunctionDescending(o1,o2, sortStep.getFieldName()));
        }
    }
}
