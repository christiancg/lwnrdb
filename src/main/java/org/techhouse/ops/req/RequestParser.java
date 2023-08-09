package org.techhouse.ops.req;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.techhouse.config.Globals;
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
    private static final Gson gson = IocContainer.get(Gson.class);

    public static OperationRequest parseRequest(final String message) throws InvalidCommandException {
        try {
            final var baseReq = gson.fromJson(message, OperationRequest.class);
            return switch (baseReq.getType()) {
                case SAVE -> {
                    final var parsed = gson.fromJson(message, SaveRequest.class);
                    if(parsed.getObject().has(Globals.PK_FIELD)) {
                        parsed.set_id(parsed.getObject().get(Globals.PK_FIELD).getAsString());
                    }
                    yield parsed;
                }
                case FIND_BY_ID -> gson.fromJson(message, FindByIdRequest.class);
                case AGGREGATE -> parseAggregationRequest(message);
                case DELETE -> gson.fromJson(message, DeleteRequest.class);
                case CREATE_DATABASE -> gson.fromJson(message, CreateDatabaseRequest.class);
                case DROP_DATABASE -> gson.fromJson(message, DropDatabaseRequest.class);
                case CREATE_COLLECTION -> gson.fromJson(message, CreateCollectionRequest.class);
                case DROP_COLLECTION -> gson.fromJson(message, DropCollectionRequest.class);
                case CLOSE_CONNECTION -> gson.fromJson(message, CloseConnectionRequest.class);
            };
        } catch (Exception e) {
            throw new InvalidCommandException(e);
        }
    }

    private static OperationRequest parseAggregationRequest(final String message) {
        final var aggRequest = gson.fromJson(message, AggregateRequest.class);
        final var steps = new ArrayList<BaseAggregationStep>();
        final var roughlyParsedAggSteps = aggRequest.getAggregationSteps();
        final var crudeArrayElements = gson.fromJson(message, JsonObject.class);
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
            case GROUP_BY -> gson.fromJson(obj, GroupByAggregationStep.class);
            case JOIN -> gson.fromJson(obj, JoinAggregationStep.class);
            case COUNT -> gson.fromJson(obj, CountAggregationStep.class);
            case DISTINCT -> gson.fromJson(obj, DistinctAggregationStep.class);
            case LIMIT -> gson.fromJson(obj, LimitAggregationStep.class);
            case SKIP -> gson.fromJson(obj, SkipAggregationStep.class);
            case SORT -> gson.fromJson(obj, SortAggregationStep.class);
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
        final var midOperationType = gson.fromJson(obj.get("type"), MidOperationType.class);
        return switch (midOperationType) {
            case AVG, SUM, SUBS, MAX, MIN, MULTIPLY, DIVIDE, POW, ROOT, CONCAT ->
                    new ArrayParamMidOperator(midOperationType, obj.get("operands").getAsJsonArray());
            case ABS, SIZE -> new OneParamMidOperator(midOperationType, obj.get("operand").getAsString());
            case CAST -> new CastMidOperator(obj.get("fieldName").getAsString(), gson.fromJson(obj.get("toType"), CastToType.class));
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
            final var operatorType = gson.fromJson(operator.get("fieldOperatorType"), FieldOperatorType.class);
            parsedOperator = new FieldOperator(operatorType, fieldName, fieldValue);
        } else {
            final var conjunctionType = gson.fromJson(operator.get("conjunctionType"), ConjunctionOperatorType.class);
            final var operators = operator.get("operators").getAsJsonArray().asList().stream().map(element -> recursiveParse(element.getAsJsonObject())).toList();
            parsedOperator = new ConjunctionOperator(conjunctionType, operators);
        }
        return parsedOperator;
    }
}
