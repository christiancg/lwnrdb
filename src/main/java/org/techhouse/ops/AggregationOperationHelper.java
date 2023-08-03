package org.techhouse.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AggregationOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    public static List<JsonObject> processAggregation(AggregateRequest request,
                                                      Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                      Map<String, Map<String, DbEntry>> collectionMap) throws ExecutionException, InterruptedException {
        Stream<JsonObject> resultStream = null;
        final var collectionIdentifier = OperationProcessor.getCollectionIdentifier(request.getDatabaseName(), request.getCollectionName());
        for (var step : request.getAggregationSteps()) {
            resultStream = switch (step.getType()) {
                case FILTER -> processFilterStep(step, resultStream, indexMap, collectionMap, collectionIdentifier);
                case MAP -> processMapStep(step, resultStream, indexMap, collectionMap, collectionIdentifier);
                case GROUP_BY -> processGroupByStep(step, resultStream, indexMap, collectionMap, collectionIdentifier);
                case JOIN -> processJoinStep(step, resultStream, indexMap, collectionMap, collectionIdentifier);
                case COUNT -> processCountStep(step, resultStream, indexMap, collectionMap, collectionIdentifier);
                case DISTINCT -> processDistinctStep(step, resultStream, indexMap, collectionMap, collectionIdentifier);
                case LIMIT -> processLimitStep(step, resultStream, indexMap, collectionMap, collectionIdentifier);
                case SKIP -> processSkipStep(step, resultStream, indexMap, collectionMap, collectionIdentifier);
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
        return OperatorHelper.processOperator(filterOperator, resultStream, indexMap, collectionMap,
                collectionIdentifier);
    }

    private static Stream<JsonObject> processMapStep(BaseAggregationStep baseMapStep,
                                                     Stream<JsonObject> resultStream,
                                                     Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                     Map<String, Map<String, DbEntry>> collectionMap,
                                                     String collectionIdentifier) {
        resultStream = resultStream != null ? resultStream : Stream.empty();
        final var mapStep = (MapAggregationStep) baseMapStep;
        return Stream.empty();
    }

    private static Stream<JsonObject> processGroupByStep(BaseAggregationStep baseGroupByStep,
                                                         Stream<JsonObject> resultStream,
                                                         Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                         Map<String, Map<String, DbEntry>> collectionMap,
                                                         String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = initializeStreamIfNecessary(resultStream, collectionMap, collectionIdentifier);
        final var groupByStep = (GroupByAggregationStep) baseGroupByStep;
        return resultStream.filter(jsonObject -> jsonObject.has(groupByStep.getFieldName()))
                .collect(Collectors.groupingBy(jsonObject -> jsonObject.get(groupByStep.getFieldName())))
                .entrySet().stream().map(jsonElementListEntry -> {
                    final var groupedEntry = new JsonObject();
                    groupedEntry.add(groupByStep.getFieldName(), jsonElementListEntry.getKey());
                    final var values = jsonElementListEntry.getValue().stream().reduce(new JsonArray(), (jsonArray, jsonObject) -> {
                        jsonArray.add(jsonObject);
                        return jsonArray;
                    }, (jsonArray, jsonArray2) -> jsonArray);
                    groupedEntry.add("group", values);
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
        return Stream.empty();
    }

    private static Stream<JsonObject> processCountStep(BaseAggregationStep baseCountStep,
                                                       Stream<JsonObject> resultStream,
                                                       Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                       Map<String, Map<String, DbEntry>> collectionMap,
                                                       String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = initializeStreamIfNecessary(resultStream, collectionMap, collectionIdentifier);
        final var countStep = (CountAggregationStep) baseCountStep;
        return Stream.empty();
    }

    private static Stream<JsonObject> processDistinctStep(BaseAggregationStep baseDistinctStep,
                                                          Stream<JsonObject> resultStream,
                                                          Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                          Map<String, Map<String, DbEntry>> collectionMap,
                                                          String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = initializeStreamIfNecessary(resultStream, collectionMap, collectionIdentifier);
        final var distinctStep = (DistinctAggregationStep) baseDistinctStep;
        return Stream.empty();
    }

    private static Stream<JsonObject> processLimitStep(BaseAggregationStep baseLimitStep,
                                                       Stream<JsonObject> resultStream,
                                                       Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                       Map<String, Map<String, DbEntry>> collectionMap,
                                                       String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = initializeStreamIfNecessary(resultStream, collectionMap, collectionIdentifier);
        final var limitStep = (LimitAggregationStep) baseLimitStep;
        return resultStream.limit(limitStep.getLimit());
    }

    private static Stream<JsonObject> processSkipStep(BaseAggregationStep baseSkipStep,
                                                      Stream<JsonObject> resultStream,
                                                      Map<String, Map<String, List<IndexEntry>>> indexMap,
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
        return Stream.empty();
    }
}
