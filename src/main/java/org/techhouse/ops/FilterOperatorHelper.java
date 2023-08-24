package org.techhouse.ops;

import com.google.gson.JsonObject;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FilterOperatorHelper {
    private static final Cache cache = IocContainer.get(Cache.class);

    public static Stream<JsonObject> processOperator(BaseOperator operator, Stream<JsonObject> resultStream,
                                                     String dbName, String collName)
            throws ExecutionException, InterruptedException {
        resultStream = switch (operator.getType()) {
            case CONJUNCTION ->
                    processConjunctionOperator((ConjunctionOperator) operator, resultStream, dbName, collName);
            case FIELD -> processFieldOperator((FieldOperator) operator, resultStream, dbName, collName);
        };
        return resultStream;
    }

    private static Stream<JsonObject> processConjunctionOperator(ConjunctionOperator operator, Stream<JsonObject> resultStream,
                                                                 String dbName, String collName)
            throws ExecutionException, InterruptedException {
        List<Stream<JsonObject>> combinationResult = new ArrayList<>();
        for (var step : operator.getOperators()) {
            Stream<JsonObject> partialResults;
            if (step.getType() == OperatorType.CONJUNCTION) {
                partialResults = processConjunctionOperator((ConjunctionOperator) step, resultStream, dbName, collName);
            } else {
                partialResults = processFieldOperator((FieldOperator) step, resultStream, dbName, collName);
            }
            combinationResult.add(partialResults);
        }
        return switch (operator.getConjunctionType()) {
            case AND -> andXorConjunction(combinationResult, operator.getOperators().size());
            case OR -> orConjunction(combinationResult);
            case XOR -> andXorConjunction(combinationResult, 1);
            case NOR -> {
                final var combined = orConjunction(combinationResult);
                yield norNandAllStreamAggregation(combined, resultStream, dbName, collName);
            }
            case NAND -> {
                final var combined = andXorConjunction(combinationResult, operator.getOperators().size());
                yield norNandAllStreamAggregation(combined, resultStream, dbName, collName);
            }
        };
    }

    private static Stream<JsonObject> andXorConjunction(List<Stream<JsonObject>> combinationResult, int matches) {
        return combinationResult.stream().flatMap(jsonObjectStream -> jsonObjectStream)
                .collect(Collectors.groupingBy(jsonObject -> jsonObject.get(Globals.PK_FIELD))).entrySet().stream()
                .filter(jsonElementListEntry -> jsonElementListEntry.getValue().size() == matches)
                .flatMap(jsonElementListEntry -> jsonElementListEntry.getValue().stream())
                .distinct();
    }

    private static Stream<JsonObject> orConjunction(List<Stream<JsonObject>> combinationResult) {
        return combinationResult.stream().flatMap(jsonObjectStream -> jsonObjectStream).distinct();
    }

    private static Stream<JsonObject> norNandAllStreamAggregation(Stream<JsonObject> combined, Stream<JsonObject> resultStream,
                                                                  String dbName, String collName) {
        if (resultStream == null) {
            resultStream = cache.getWholeCollection(dbName, collName).values().stream().map(DbEntry::getData);
        }
        return Stream.concat(resultStream, combined).collect(Collectors.groupingBy(jsonObject -> jsonObject.get(Globals.PK_FIELD)))
                .entrySet().stream().filter(jsonElementListEntry -> jsonElementListEntry.getValue().size() == 1)
                .flatMap(jsonElementListEntry -> jsonElementListEntry.getValue().stream());
    }

    private static Stream<JsonObject> processFieldOperator(FieldOperator operator, Stream<JsonObject> resultStream,
                                                           String dbName, String collName) throws ExecutionException, InterruptedException {

        final var tester = getTester(operator, operator.getFieldOperatorType());
        return internalBaseFiltering(tester, operator, resultStream, dbName, collName);
    }

    public static BiPredicate<JsonObject, String> getTester(FieldOperator operator, FieldOperatorType operation) {
        return (JsonObject toTest, String fieldName) -> {
            final var operatorElement = operator.getValue();
            if (JsonUtils.hasInPath(toTest, fieldName)) {
                final var toTestElement = JsonUtils.getFromPath(toTest, fieldName);
                if (operatorElement.isJsonPrimitive()) {
                    if (toTestElement.isJsonPrimitive()) {
                        final var operatorPrimitive = operatorElement.getAsJsonPrimitive();
                        final var toTestPrimitive = toTestElement.getAsJsonPrimitive();
                        if (operatorPrimitive.isBoolean() && toTestPrimitive.isBoolean()) {
                            if (operation == FieldOperatorType.EQUALS) {
                                return operatorPrimitive.getAsBoolean() == toTestPrimitive.getAsBoolean();
                            } else if (operation == FieldOperatorType.NOT_EQUALS) {
                                return operatorPrimitive.getAsBoolean() != toTestPrimitive.getAsBoolean();
                            }
                            return false;
                        } else if (operatorPrimitive.isNumber() && toTestPrimitive.isNumber()) {
                            return switch (operation) {
                                case EQUALS -> operatorPrimitive.getAsDouble() == toTestPrimitive.getAsDouble();
                                case NOT_EQUALS -> operatorPrimitive.getAsDouble() != toTestPrimitive.getAsDouble();
                                case GREATER_THAN -> operatorPrimitive.getAsDouble() < toTestPrimitive.getAsDouble();
                                case GREATER_THAN_EQUALS ->
                                        operatorPrimitive.getAsDouble() <= toTestPrimitive.getAsDouble();
                                case SMALLER_THAN -> operatorPrimitive.getAsDouble() > toTestPrimitive.getAsDouble();
                                case SMALLER_THAN_EQUALS ->
                                        operatorPrimitive.getAsDouble() >= toTestPrimitive.getAsDouble();
                                case IN, NOT_IN, CONTAINS -> false;
                            };
                        } else if (operatorPrimitive.isString() && toTestPrimitive.isString()) {
                            return switch (operation) {
                                case EQUALS ->
                                        operatorPrimitive.getAsString().equalsIgnoreCase(toTestPrimitive.getAsString());
                                case NOT_EQUALS ->
                                        !operatorPrimitive.getAsString().equalsIgnoreCase(toTestPrimitive.getAsString());
                                case CONTAINS ->
                                        toTestPrimitive.getAsString().contains(operatorPrimitive.getAsString());
                                case GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS, IN, NOT_IN ->
                                        false;
                            };
                        } else return operatorPrimitive.isJsonNull() && toTestPrimitive.isJsonNull();
                    }
                } else if (operatorElement.isJsonArray()) {
                    if (toTestElement != null && toTestElement.isJsonPrimitive()) {
                        final var jsonArray = operatorElement.getAsJsonArray();
                        final var result = jsonArray.contains(toTestElement.getAsJsonPrimitive());
                        return (operation == FieldOperatorType.IN) == result;
                    }
                }
            }
            return false;
        };
    }

    private static Stream<JsonObject> internalBaseFiltering(BiPredicate<JsonObject, String> test, FieldOperator operator,
                                                            Stream<JsonObject> resultStream, String dbName, String collName)
            throws ExecutionException, InterruptedException {
        final var fieldName = operator.getField();
        final var value = operator.getValue();
        Set<String> matchingValues = null;
        if (value.isJsonPrimitive() || value.isJsonArray()) {
            if (value.isJsonArray()) {
                matchingValues = cache.getIdsFromIndex(dbName, collName, fieldName, operator, value.getAsJsonArray());
            } else {
                final var primitive = value.getAsJsonPrimitive();
                if (primitive.isBoolean()) {
                    matchingValues = cache.getIdsFromIndex(dbName, collName, fieldName, operator, value.getAsBoolean());
                } else if (primitive.isNumber()) {
                    matchingValues = cache.getIdsFromIndex(dbName, collName, fieldName, operator, value.getAsDouble());
                } else if (primitive.isString()) {
                    matchingValues = cache.getIdsFromIndex(dbName, collName, fieldName, operator, value.getAsString());
                }
            }
        }
        final var coll = cache.getWholeCollection(dbName, collName);
        if (matchingValues != null) {
            final var partialList = new ArrayList<JsonObject>();
            for (var match : matchingValues) {
                partialList.add(coll.get(match).getData());
            }
            if (resultStream == null) {
                resultStream = partialList.stream();
            } else {
                resultStream = resultStream.filter(partialList::contains);
            }
        } else {
            resultStream = coll.values().stream().map(DbEntry::getData)
                    .filter(data -> test.test(data, fieldName));
        }
        return resultStream;
    }
}
