package org.techhouse.ops.req;

import org.techhouse.config.Globals;
import org.techhouse.ejson.*;
import org.techhouse.ejson2.elements.JsonBaseElement;
import org.techhouse.ejson2.elements.JsonBoolean;
import org.techhouse.ejson2.elements.JsonDouble;
import org.techhouse.ejson2.elements.JsonString;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.*;
import org.techhouse.ops.req.agg.mid_operators.*;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.*;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;

import java.util.ArrayList;
import java.util.List;

public class RequestParser {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final org.techhouse.ejson2.EJson eJsonNew = IocContainer.get(org.techhouse.ejson2.EJson.class);

    public static OperationRequest parseRequest(final String message) throws InvalidCommandException {
        try {
            final var baseReq = eJson.fromJson(message, OperationRequest.class);
            return switch (baseReq.getType()) {
                case SAVE -> {
                    final var parsed = eJson.fromJson(message, SaveRequest.class);
                    final var toTest = eJsonNew.fromJson(message, SaveRequest.class);
                    System.out.println("new: " + eJsonNew.toJson(toTest));
                    System.out.println("old: " + eJson.toJson(parsed));
                    if(parsed.getObject().has(Globals.PK_FIELD)) {
                        parsed.set_id(parsed.getObject().get(Globals.PK_FIELD).getAsString());
                    }
                    yield parsed;
                }
                case FIND_BY_ID -> {
                    final var parsed = eJson.fromJson(message, FindByIdRequest.class);
                    final var toTest = eJsonNew.fromJson(message, FindByIdRequest.class);
                    System.out.println("new: " + eJsonNew.toJson(toTest));
                    System.out.println("old: " + eJson.toJson(parsed));
                    yield parsed;
                }
                case AGGREGATE -> {
                    final var parsed = parseAggregationRequest(message);
                    final var newAggregationRequest = newParseAggregationRequest(message);
                    System.out.println("new: " + eJsonNew.toJson(newAggregationRequest));
                    System.out.println("old: " + eJson.toJson(parsed));
                    yield parsed;
                }
                case DELETE -> {
                    final var parsed = eJson.fromJson(message, DeleteRequest.class);
                    final var toTest = eJsonNew.fromJson(message, DeleteRequest.class);
                    System.out.println("new: " + eJsonNew.toJson(toTest));
                    System.out.println("old: " + eJson.toJson(parsed));
                    yield parsed;
                }
                case CREATE_DATABASE -> {
                    final var parsed = eJson.fromJson(message, CreateDatabaseRequest.class);
                    final var toTest = eJsonNew.fromJson(message, CreateDatabaseRequest.class);
                    System.out.println("new: " + eJsonNew.toJson(toTest));
                    System.out.println("old: " + eJson.toJson(parsed));
                    yield parsed;
                }
                case DROP_DATABASE -> {
                    final var parsed = eJson.fromJson(message, DropDatabaseRequest.class);
                    final var toTest = eJsonNew.fromJson(message, DropDatabaseRequest.class);
                    System.out.println("new: " + eJsonNew.toJson(toTest));
                    System.out.println("old: " + eJson.toJson(parsed));
                    yield parsed;
                }
                case CREATE_COLLECTION -> {
                    final var parsed = eJson.fromJson(message, CreateCollectionRequest.class);
                    final var toTest = eJsonNew.fromJson(message, CreateCollectionRequest.class);
                    System.out.println("new: " + eJsonNew.toJson(toTest));
                    System.out.println("old: " + eJson.toJson(parsed));
                    yield parsed;
                }
                case DROP_COLLECTION -> {
                    final var parsed = eJson.fromJson(message, DropCollectionRequest.class);
                    final var toTest = eJsonNew.fromJson(message, DropCollectionRequest.class);
                    System.out.println("new: " + eJsonNew.toJson(toTest));
                    System.out.println("old: " + eJson.toJson(parsed));
                    yield parsed;
                }
                case CREATE_INDEX -> {
                    final var parsed = eJson.fromJson(message, CreateIndexRequest.class);
                    final var toTest = eJsonNew.fromJson(message, CreateIndexRequest.class);
                    System.out.println("new: " + eJsonNew.toJson(toTest));
                    System.out.println("old: " + eJson.toJson(parsed));
                    yield parsed;
                }
                case DROP_INDEX -> {
                    final var parsed = eJson.fromJson(message, DropIndexRequest.class);
                    final var toTest = eJsonNew.fromJson(message, DropIndexRequest.class);
                    System.out.println("new: " + eJsonNew.toJson(toTest));
                    System.out.println("old: " + eJson.toJson(parsed));
                    yield parsed;
                }
                case CLOSE_CONNECTION -> {
                    final var parsed = eJson.fromJson(message, CloseConnectionRequest.class);
                    final var toTest = eJsonNew.fromJson(message, CloseConnectionRequest.class);
                    System.out.println("new: " + eJsonNew.toJson(toTest));
                    System.out.println("old: " + eJson.toJson(parsed));
                    yield parsed;
                }
            };
        } catch (Exception e) {
            throw new InvalidCommandException(e);
        }
    }

    private static OperationRequest parseAggregationRequest(final String message) {
        final var aggRequest = eJson.fromJson(message, AggregateRequest.class);
        final var steps = new ArrayList<BaseAggregationStep>();
        final var roughlyParsedAggSteps = aggRequest.getAggregationSteps();
        final var crudeArrayElements = eJson.fromJson(message, JsonObject.class);
        final var jsonArray = crudeArrayElements.get("aggregationSteps").getAsJsonArray();
        for (var i=0; i < roughlyParsedAggSteps.size(); i++) {
            final var type = roughlyParsedAggSteps.get(i).getType();
            steps.add(parseAggregationStep(type, jsonArray, i));
        }
        aggRequest.setAggregationSteps(steps);
        return aggRequest;
    }

    private static OperationRequest newParseAggregationRequest(final String message) throws Exception {
        final var aggRequest = eJsonNew.fromJson(message, AggregateRequest.class);
        final var steps = new ArrayList<BaseAggregationStep>();
        final var roughlyParsedAggSteps = aggRequest.getAggregationSteps();
        final var baseObject = eJsonNew.fromJson(message, org.techhouse.ejson2.elements.JsonObject.class);
        final var jsonArray = baseObject.get("aggregationSteps").asJsonArray();
        for (var i=0; i < roughlyParsedAggSteps.size(); i++) {
            final var type = roughlyParsedAggSteps.get(i).getType();
            steps.add(newParseAggregationStep(type, jsonArray, i));
        }
        aggRequest.setAggregationSteps(steps);
        return aggRequest;
    }

    private static BaseAggregationStep parseAggregationStep(final AggregationStepType type, final JsonArray jsonArray, final int index) {
        final var obj = jsonArray.get(index).getAsJsonObject();
        return switch (type) {
            case FILTER -> parseFilterStep(obj);
            case MAP -> parseMapStep(obj);
            case GROUP_BY -> eJson.fromJson(obj, GroupByAggregationStep.class);
            case JOIN -> eJson.fromJson(obj, JoinAggregationStep.class);
            case COUNT -> eJson.fromJson(obj, CountAggregationStep.class);
            case DISTINCT -> eJson.fromJson(obj, DistinctAggregationStep.class);
            case LIMIT -> eJson.fromJson(obj, LimitAggregationStep.class);
            case SKIP -> eJson.fromJson(obj, SkipAggregationStep.class);
            case SORT -> eJson.fromJson(obj, SortAggregationStep.class);
        };
    }

    private static BaseAggregationStep newParseAggregationStep(final AggregationStepType type, final org.techhouse.ejson2.elements.JsonArray jsonArray, final int index) throws Exception {
        final var obj = jsonArray.get(index).asJsonObject();
        return switch (type) {
            case FILTER -> newParseFilterStep(obj);
            case MAP -> newParseMapStep(obj);
            case GROUP_BY -> eJsonNew.fromJson(obj, GroupByAggregationStep.class);
            case JOIN -> eJsonNew.fromJson(obj, JoinAggregationStep.class);
            case COUNT -> eJsonNew.fromJson(obj, CountAggregationStep.class);
            case DISTINCT -> eJsonNew.fromJson(obj, DistinctAggregationStep.class);
            case LIMIT -> eJsonNew.fromJson(obj, LimitAggregationStep.class);
            case SKIP -> eJsonNew.fromJson(obj, SkipAggregationStep.class);
            case SORT -> eJsonNew.fromJson(obj, SortAggregationStep.class);
        };
    }

    private static BaseAggregationStep newParseMapStep(final org.techhouse.ejson2.elements.JsonObject obj) throws Exception {
        final var operators = obj.get("operators").asJsonArray();
        return new MapAggregationStep(newParseMapOperators(operators));
    }

    private static BaseAggregationStep parseMapStep(final JsonObject obj) {
        final var operators = obj.get("operators").getAsJsonArray();
        return new MapAggregationStep(parseMapOperators(operators));
    }

    private static List<MapOperator> newParseMapOperators(final org.techhouse.ejson2.elements.JsonArray arr) throws Exception {
        final var mapOperators = new ArrayList<MapOperator>();
        for (var mapStep : arr.asList()) {
            final var mapStepObj = mapStep.asJsonObject();
            final var condition = mapStepObj.get("condition");
            BaseOperator parsedCondition = null;
            if (condition != null && condition.getJsonType() != JsonBaseElement.JsonType.NULL) {
                parsedCondition = newRecursiveParse(condition.asJsonObject());
            }
            final var fieldName = mapStepObj.get("fieldName").asJsonString().getValue();
            final var operator = mapStepObj.get("operator");
            MapOperator parsed;
            if (operator != null) {
                final var operatorObj = operator.asJsonObject();
                parsed = new AddFieldMapOperator(fieldName, parsedCondition, newParseMidOperator(operatorObj));
            } else {
                parsed = new RemoveFieldMapOperator(fieldName, parsedCondition);
            }
            mapOperators.add(parsed);
        }
        return mapOperators;
    }

    private static List<MapOperator> parseMapOperators(final JsonArray arr) {
        final var mapOperators = new ArrayList<MapOperator>();
        for (var mapStep : arr.asList()) {
            final var mapStepObj = mapStep.getAsJsonObject();
            final var condition = mapStepObj.get("condition");
            BaseOperator parsedCondition = null;
            if (condition != null && !condition.isJsonNull()) {
                parsedCondition = recursiveParse(condition.getAsJsonObject());
            }
            final var fieldName = mapStepObj.get("fieldName").getAsString();
            final var operator = mapStepObj.get("operator");
            MapOperator parsed;
            if (operator != null) {
                final var operatorObj = operator.getAsJsonObject();
                parsed = new AddFieldMapOperator(fieldName, parsedCondition, parseMidOperator(operatorObj));
            } else {
                parsed = new RemoveFieldMapOperator(fieldName, parsedCondition);
            }
            mapOperators.add(parsed);
        }
        return mapOperators;
    }

    private static BaseMidOperator newParseMidOperator(final org.techhouse.ejson2.elements.JsonObject obj) throws Exception {
        final var midOperationType = eJsonNew.fromJson(obj.get("type"), MidOperationType.class);
        return switch (midOperationType) {
            case AVG, SUM, SUBS, MAX, MIN, MULTIPLY, DIVIDE, POW, ROOT, CONCAT -> {
                // TODO: remove next lines when old EJson is removed, just here to cast to old JsonElement class
                final var newEjsonArray = obj.get("operands").asJsonArray();
                final var str = eJsonNew.toJson(newEjsonArray);
                final var oldJsonArr = eJson.fromJson(str, JsonArray.class);
                yield new ArrayParamMidOperator(midOperationType, oldJsonArr);
            }
            case ABS, SIZE -> new OneParamMidOperator(midOperationType, obj.get("operand").asJsonString().getValue());
            case CAST -> new CastMidOperator(obj.get("fieldName").asJsonString().getValue(), eJsonNew.fromJson(obj.get("toType"), CastToType.class));
        };
    }

    private static BaseMidOperator parseMidOperator(final JsonObject obj) {
        final var midOperationType = eJson.fromJson(obj.get("type"), MidOperationType.class);
        return switch (midOperationType) {
            case AVG, SUM, SUBS, MAX, MIN, MULTIPLY, DIVIDE, POW, ROOT, CONCAT ->
                    new ArrayParamMidOperator(midOperationType, obj.get("operands").getAsJsonArray());
            case ABS, SIZE -> new OneParamMidOperator(midOperationType, obj.get("operand").getAsString());
            case CAST -> new CastMidOperator(obj.get("fieldName").getAsString(), eJson.fromJson(obj.get("toType"), CastToType.class));
        };
    }

    private static BaseAggregationStep newParseFilterStep(final org.techhouse.ejson2.elements.JsonObject obj) throws Exception {
        final var operator = obj.get("operator").asJsonObject();
        return new FilterAggregationStep(newRecursiveParse(operator));
    }

    private static BaseAggregationStep parseFilterStep(final JsonObject obj) {
        final var operator = obj.get("operator").getAsJsonObject();
        return new FilterAggregationStep(recursiveParse(operator));
    }

    private static BaseOperator newRecursiveParse(final org.techhouse.ejson2.elements.JsonObject operator) throws Exception {
        BaseOperator parsedOperator;
        if (operator.has("fieldOperatorType")) {
            final var fieldName = operator.get("field").asJsonString().getValue();
            final var fieldValue = operator.get("value");
            final var operatorType = eJsonNew.fromJson(operator.get("fieldOperatorType"), FieldOperatorType.class);
            // TODO: remove next line when old EJson is removed, just here to cast to old JsonElement class
            final var oldJsonElement = switch (fieldValue) {
                case JsonString string -> new JsonPrimitive(string.getValue());
                case JsonDouble d -> new JsonPrimitive(d.getValue());
                case JsonBoolean b -> new JsonPrimitive(b.getValue());
                case org.techhouse.ejson2.elements.JsonArray a -> eJson.fromJson(eJsonNew.toJson(a), JsonArray.class);
                default -> throw new IllegalStateException("Unexpected value: " + fieldValue);
            };
            parsedOperator = new FieldOperator(operatorType, fieldName, oldJsonElement);
        } else {
            final var conjunctionType = eJsonNew.fromJson(operator.get("conjunctionType"), ConjunctionOperatorType.class);
            final var operators = operator.get("operators").asJsonArray().asList().stream().map(element -> {
                try {
                    return newRecursiveParse(element.asJsonObject());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();
            parsedOperator = new ConjunctionOperator(conjunctionType, operators);
        }
        return parsedOperator;
    }

    private static BaseOperator recursiveParse(final JsonObject operator) {
        BaseOperator parsedOperator;
        if (operator.has("fieldOperatorType")) {
            final var fieldName = operator.get("field").getAsString();
            final var fieldValue = operator.get("value");
            final var operatorType = eJson.fromJson(operator.get("fieldOperatorType"), FieldOperatorType.class);
            parsedOperator = new FieldOperator(operatorType, fieldName, fieldValue);
        } else {
            final var conjunctionType = eJson.fromJson(operator.get("conjunctionType"), ConjunctionOperatorType.class);
            final var operators = operator.get("operators").getAsJsonArray().asList().stream().map(element -> recursiveParse(element.getAsJsonObject())).toList();
            parsedOperator = new ConjunctionOperator(conjunctionType, operators);
        }
        return parsedOperator;
    }
}
