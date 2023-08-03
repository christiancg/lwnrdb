package org.techhouse.ops.req;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.*;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.*;

import java.util.ArrayList;

public class RequestParser {
    private static final Gson gson = IocContainer.get(Gson.class);

    public static OperationRequest parseRequest(String message) throws InvalidCommandException {
        try {
            final var baseReq = gson.fromJson(message, OperationRequest.class);
            return switch (baseReq.getType()) {
                case SAVE -> {
                    final var parsed = gson.fromJson(message, SaveRequest.class);
                    if(parsed.getObject().has("_id")) {
                        parsed.set_id(parsed.getObject().get("_id").getAsString());
                    }
                    yield parsed;
                }
                case FIND_BY_ID -> gson.fromJson(message, FindByIdRequest.class);
                case AGGREGATE -> parseAggregationRequest(message);
                case DELETE -> gson.fromJson(message, DeleteRequest.class);
                case CREATE_DATABASE -> gson.fromJson(message, CreateDatabaseRequest.class);
                case CREATE_COLLECTION -> gson.fromJson(message, CreateCollectionRequest.class);
                case CLOSE_CONNECTION -> gson.fromJson(message, CloseConnectionRequest.class);
            };
        } catch (Exception e) {
            throw new InvalidCommandException(e);
        }
    }

    private static OperationRequest parseAggregationRequest(String message) {
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

    private static BaseAggregationStep parseAggregationStep(AggregationStepType type, JsonArray jsonArray, int index) {
        final var obj = jsonArray.get(index).getAsJsonObject();
        return switch (type) {
            case FILTER -> parseFilterStep(obj);
            case MAP -> gson.fromJson(obj, MapAggregationStep.class);
            case GROUP_BY -> gson.fromJson(obj, GroupByAggregationStep.class);
            case JOIN -> gson.fromJson(obj, JoinAggregationStep.class);
            case COUNT -> gson.fromJson(obj, CountAggregationStep.class);
            case DISTINCT -> gson.fromJson(obj, DistinctAggregationStep.class);
            case LIMIT -> gson.fromJson(obj, LimitAggregationStep.class);
            case SKIP -> gson.fromJson(obj, SkipAggregationStep.class);
            case SORT -> gson.fromJson(obj, SortAggregationStep.class);
        };
    }

    private static BaseAggregationStep parseFilterStep(JsonObject obj) {
        final var operator = obj.get("operator").getAsJsonObject();
        return new FilterAggregationStep(recursiveParse(operator));
    }

    private static BaseOperator recursiveParse(JsonObject operator) {
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
