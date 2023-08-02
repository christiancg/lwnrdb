package org.techhouse.ops;

import com.google.gson.JsonObject;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.FieldOperatorType;
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

        final var tester = getTester(operator, operator.getFieldOperatorType());
        return internalBaseFiltering(tester, operator, resultStream, indexMap, collectionMap, collectionIdentifier);
    }

    private static BiPredicate<JsonObject, String> getTester(BaseFieldOperator operator, FieldOperatorType operation) {
        return (JsonObject toTest, String fieldName) -> {
            final var operatorElement = operator.getValue();
            if (toTest.has(fieldName)) {
                final var toTestElement = toTest.get(fieldName);
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
                    if (toTestElement.isJsonPrimitive()) {
                        final var jsonArray = operatorElement.getAsJsonArray();
                        final var result = jsonArray.contains(toTestElement.getAsJsonPrimitive());
                        return (operation == FieldOperatorType.IN) == result;
                    }
                }
            }
            return false;
        };
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
