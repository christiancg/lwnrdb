package org.techhouse.ops;

import com.google.gson.JsonObject;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.operators.conjunction.BaseConjunctionOperator;
import org.techhouse.ops.req.agg.operators.field.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

public class OperatorHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    public static Stream<JsonObject> processOperator(BaseOperator operator,
                                                     Stream<JsonObject> resultStream,
                                                     Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                     Map<String, Map<String, DbEntry>> collectionMap,
                                                     String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = switch (operator.getType()) {
            case CONJUNCTION ->
                    processConjunctionOperator((BaseConjunctionOperator) operator, resultStream, indexMap, collectionMap,
                            collectionIdentifier);
            case FIELD -> processFieldOperator((BaseFieldOperator) operator, resultStream, indexMap, collectionMap,
                    collectionIdentifier);
        };
        return resultStream;
    }

    private static Stream<JsonObject> processConjunctionOperator(BaseConjunctionOperator operator,
                                                                 Stream<JsonObject> resultStream,
                                                                 Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                                 Map<String, Map<String, DbEntry>> collectionMap,
                                                                 String collectionIdentifier) {
        return Stream.empty();
    }

    private static Stream<JsonObject> processFieldOperator(BaseFieldOperator operator,
                                                           Stream<JsonObject> resultStream,
                                                           Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                           Map<String, Map<String, DbEntry>> collectionMap,
                                                           String collectionIdentifier) throws ExecutionException, InterruptedException {
        resultStream = switch (operator.getFieldOperatorType()) {
            case EQUALS -> equalsOperator((EqualsOperator) operator, resultStream, indexMap, collectionMap, collectionIdentifier);
            case NOT_EQUALS -> notEqualsOperator((NotEqualsOperator) operator, resultStream, indexMap, collectionMap, collectionIdentifier);
            case GREATER_THAN -> greaterThanOperator((GreaterThanOperator) operator, resultStream, indexMap, collectionMap, collectionIdentifier);
            case GREATER_THAN_EQUALS -> greaterThanEqualsOperator((GreaterThanEqualsOperator) operator, resultStream, indexMap, collectionMap, collectionIdentifier);
            case SMALLER_THAN -> smallerThanOperator((SmallerThanOperator) operator, resultStream, indexMap, collectionMap, collectionIdentifier);
            case SMALLER_THAN_EQUALS -> smallerThanEqualsOperator((SmallerThanEqualsOperator) operator, resultStream, indexMap, collectionMap, collectionIdentifier);
            case IN -> inOperator((InOperator) operator, resultStream, indexMap, collectionMap, collectionIdentifier);
            case NOT_IN -> notInOperator((NotInOperator) operator, resultStream, indexMap, collectionMap, collectionIdentifier);
        };
        return resultStream;
    }

    enum Operation {
        EQUALS,
        NOT_EQUALS,
        GRATER_THAN,
        GREATER_THAN_EQUALS,
        SMALLER_THAN,
        SMALLER_THAN_EQUALS,
        IN,
        NOT_IN
    }

    private static BiPredicate<JsonObject, String> getTester(BaseFieldOperator operator, Operation operation) {
        return (JsonObject toTest, String fieldName) -> {
            final var operatorElement = operator.getValue();
            if (toTest.has(fieldName)) {
                final var toTestElement = toTest.get(fieldName);
                if (operatorElement.isJsonPrimitive()) {
                    if (toTestElement.isJsonPrimitive()) {
                        final var operatorPrimitive = operatorElement.getAsJsonPrimitive();
                        final var toTestPrimitive = toTestElement.getAsJsonPrimitive();
                        if (operatorPrimitive.isBoolean() && toTestPrimitive.isBoolean()) {
                            if (operation == Operation.EQUALS) {
                                return operatorPrimitive.getAsBoolean() == toTestPrimitive.getAsBoolean();
                            } else if (operation == Operation.NOT_EQUALS) {
                                return operatorPrimitive.getAsBoolean() != toTestPrimitive.getAsBoolean();
                            }
                            return false;
                        } else if (operatorPrimitive.isNumber() && toTestPrimitive.isNumber()) {
                            return switch (operation) {
                                case EQUALS -> operatorPrimitive.getAsDouble() == toTestPrimitive.getAsDouble();
                                case NOT_EQUALS -> operatorPrimitive.getAsDouble() != toTestPrimitive.getAsDouble();
                                case GRATER_THAN -> operatorPrimitive.getAsDouble() < toTestPrimitive.getAsDouble();
                                case GREATER_THAN_EQUALS -> operatorPrimitive.getAsDouble() <= toTestPrimitive.getAsDouble();
                                case SMALLER_THAN -> operatorPrimitive.getAsDouble() > toTestPrimitive.getAsDouble();
                                case SMALLER_THAN_EQUALS -> operatorPrimitive.getAsDouble() >= toTestPrimitive.getAsDouble();
                                case IN, NOT_IN -> false;
                            };
                        } else if (operatorPrimitive.isString() && toTestPrimitive.isString()) {
                            return switch (operation) {
                                case EQUALS -> operatorPrimitive.getAsString().equalsIgnoreCase(toTestPrimitive.getAsString());
                                case NOT_EQUALS -> !operatorPrimitive.getAsString().equalsIgnoreCase(toTestPrimitive.getAsString());
                                case GRATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS, IN, NOT_IN -> false;
                            };
                        } else return operatorPrimitive.isJsonNull() && toTestPrimitive.isJsonNull();
                    }
                } else if (operatorElement.isJsonArray()) {
                    if (toTestElement.isJsonPrimitive()) {
                        final var jsonArray = operatorElement.getAsJsonArray();
                        final var result = jsonArray.contains(toTestElement.getAsJsonPrimitive());
                        return (operation == Operation.IN) == result;
                    }
                }
            }
            return false;
        };
    }

    private static Stream<JsonObject> equalsOperator(EqualsOperator operator,
                                                     Stream<JsonObject> resultStream,
                                                     Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                     Map<String, Map<String, DbEntry>> collectionMap,
                                                     String collectionIdentifier) throws ExecutionException, InterruptedException {
        final BiPredicate<JsonObject, String> tester = getTester(operator, Operation.EQUALS);
        return internalBaseFiltering(tester, operator, resultStream, indexMap, collectionMap, collectionIdentifier);
    }

    private static Stream<JsonObject> notEqualsOperator(NotEqualsOperator operator,
                                                        Stream<JsonObject> resultStream,
                                                        Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                        Map<String, Map<String, DbEntry>> collectionMap,
                                                        String collectionIdentifier) throws ExecutionException, InterruptedException {
        final BiPredicate<JsonObject, String> tester = getTester(operator, Operation.NOT_EQUALS);
        return internalBaseFiltering(tester, operator, resultStream, indexMap, collectionMap, collectionIdentifier);
    }

    private static Stream<JsonObject> greaterThanOperator(GreaterThanOperator operator,
                                                          Stream<JsonObject> resultStream,
                                                          Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                          Map<String, Map<String, DbEntry>> collectionMap,
                                                          String collectionIdentifier) throws ExecutionException, InterruptedException {
        final BiPredicate<JsonObject, String> tester = getTester(operator, Operation.GRATER_THAN);
        return internalBaseFiltering(tester, operator, resultStream, indexMap, collectionMap, collectionIdentifier);
    }

    private static Stream<JsonObject> greaterThanEqualsOperator(GreaterThanEqualsOperator operator,
                                                                Stream<JsonObject> resultStream,
                                                                Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                                Map<String, Map<String, DbEntry>> collectionMap,
                                                                String collectionIdentifier) throws ExecutionException, InterruptedException {
        final BiPredicate<JsonObject, String> tester = getTester(operator, Operation.GREATER_THAN_EQUALS);
        return internalBaseFiltering(tester, operator, resultStream, indexMap, collectionMap, collectionIdentifier);
    }

    private static Stream<JsonObject> smallerThanOperator(SmallerThanOperator operator,
                                                          Stream<JsonObject> resultStream,
                                                          Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                          Map<String, Map<String, DbEntry>> collectionMap,
                                                          String collectionIdentifier) throws ExecutionException, InterruptedException {
        final BiPredicate<JsonObject, String> tester = getTester(operator, Operation.SMALLER_THAN);
        return internalBaseFiltering(tester, operator, resultStream, indexMap, collectionMap, collectionIdentifier);
    }

    private static Stream<JsonObject> smallerThanEqualsOperator(SmallerThanEqualsOperator operator,
                                                                Stream<JsonObject> resultStream,
                                                                Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                                Map<String, Map<String, DbEntry>> collectionMap,
                                                                String collectionIdentifier) throws ExecutionException, InterruptedException {
        final BiPredicate<JsonObject, String> tester = getTester(operator, Operation.SMALLER_THAN_EQUALS);
        return internalBaseFiltering(tester, operator, resultStream, indexMap, collectionMap, collectionIdentifier);
    }

    private static Stream<JsonObject> inOperator(InOperator operator,
                                                 Stream<JsonObject> resultStream,
                                                 Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                 Map<String, Map<String, DbEntry>> collectionMap,
                                                 String collectionIdentifier) throws ExecutionException, InterruptedException {
        final BiPredicate<JsonObject, String> tester = getTester(operator, Operation.IN);
        return internalBaseFiltering(tester, operator, resultStream, indexMap, collectionMap, collectionIdentifier);
    }

    private static Stream<JsonObject> notInOperator(NotInOperator operator,
                                                 Stream<JsonObject> resultStream,
                                                 Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                 Map<String, Map<String, DbEntry>> collectionMap,
                                                 String collectionIdentifier) throws ExecutionException, InterruptedException {
        final BiPredicate<JsonObject, String> tester = getTester(operator, Operation.NOT_IN);
        return internalBaseFiltering(tester, operator, resultStream, indexMap, collectionMap, collectionIdentifier);
    }

    private static Stream<JsonObject> internalBaseFiltering(BiPredicate<JsonObject, String> test,
                                                            BaseFieldOperator operator,
                                                            Stream<JsonObject> resultStream,
                                                            Map<String, Map<String, List<IndexEntry>>> indexMap,
                                                            Map<String, Map<String, DbEntry>> collectionMap,
                                                            String collectionIdentifier) throws ExecutionException, InterruptedException {
        if (resultStream != null) {
            resultStream = resultStream.filter(jsonObject -> test.test(jsonObject, operator.getField()));
        } else {
            var coll = collectionMap.get(collectionIdentifier);
            if (coll == null) {
                coll = fs.readWholeCollection(collectionIdentifier);
                collectionMap.put(collectionIdentifier, coll);
            }
            resultStream = coll.values().stream().map(DbEntry::getData)
                    .filter(data -> test.test(data, operator.getField()));
        }
        return resultStream;
    }
}
