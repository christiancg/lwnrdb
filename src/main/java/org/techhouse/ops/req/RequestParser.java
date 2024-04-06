package org.techhouse.ops.req;

import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.JsonArray;
import org.techhouse.ejson.JsonObject;
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
            final var parsedResponse = eJsonNew.fromJson(message, OperationRequest.class);
            System.out.println(eJsonNew.toJson(parsedResponse));
            return switch (baseReq.getType()) {
                case SAVE -> {
                    final var parsed = eJson.fromJson(message, SaveRequest.class);
                    final var toTest = eJsonNew.fromJson(message, SaveRequest.class);
                    System.out.println(eJsonNew.toJson(toTest));
                    System.out.println(eJson.toJson(parsed));
                    if(parsed.getObject().has(Globals.PK_FIELD)) {
                        parsed.set_id(parsed.getObject().get(Globals.PK_FIELD).getAsString());
                    }
                    yield parsed;
                }
                case FIND_BY_ID -> eJson.fromJson(message, FindByIdRequest.class);
                case AGGREGATE -> parseAggregationRequest(message);
                case DELETE -> eJson.fromJson(message, DeleteRequest.class);
                case CREATE_DATABASE -> eJson.fromJson(message, CreateDatabaseRequest.class);
                case DROP_DATABASE -> eJson.fromJson(message, DropDatabaseRequest.class);
                case CREATE_COLLECTION -> eJson.fromJson(message, CreateCollectionRequest.class);
                case DROP_COLLECTION -> eJson.fromJson(message, DropCollectionRequest.class);
                case CREATE_INDEX -> eJson.fromJson(message, CreateIndexRequest.class);
                case DROP_INDEX -> eJson.fromJson(message, DropIndexRequest.class);
                case CLOSE_CONNECTION -> eJson.fromJson(message, CloseConnectionRequest.class);
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

    private static BaseAggregationStep parseMapStep(final JsonObject obj) {
        final var operators = obj.get("operators").getAsJsonArray();
        return new MapAggregationStep(parseMapOperators(operators));
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

    private static BaseMidOperator parseMidOperator(final JsonObject obj) {
        final var midOperationType = eJson.fromJson(obj.get("type"), MidOperationType.class);
        return switch (midOperationType) {
            case AVG, SUM, SUBS, MAX, MIN, MULTIPLY, DIVIDE, POW, ROOT, CONCAT ->
                    new ArrayParamMidOperator(midOperationType, obj.get("operands").getAsJsonArray());
            case ABS, SIZE -> new OneParamMidOperator(midOperationType, obj.get("operand").getAsString());
            case CAST -> new CastMidOperator(obj.get("fieldName").getAsString(), eJson.fromJson(obj.get("toType"), CastToType.class));
        };
    }

    private static BaseAggregationStep parseFilterStep(final JsonObject obj) {
        final var operator = obj.get("operator").getAsJsonObject();
        return new FilterAggregationStep(recursiveParse(operator));
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
