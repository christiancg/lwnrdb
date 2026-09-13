package org.techhouse.ops.req;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.BaseMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastToType;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.ScriptMidOperator;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.operators.ScriptOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;

public final class RequestParser {
    private RequestParser() {
    }

    private static final EJson eJson = IocContainer.get(EJson.class);

    public static OperationRequest parseRequest(final String message) throws InvalidCommandException {
        try {
            final var root = eJson.fromJson(message, JsonObject.class);
            final var baseReq = eJson.fromJson(root, OperationRequest.class);
            return switch (baseReq.getType()) {
                case BULK_SAVE -> eJson.fromJson(root, BulkSaveRequest.class);
                case SAVE -> {
                    final var parsed = eJson.fromJson(root, SaveRequest.class);
                    if (parsed.getObject().has(Globals.PK_FIELD)) {
                        parsed.set_id(parsed.getObject().get(Globals.PK_FIELD).asJsonString().getValue());
                    }
                    yield parsed;
                }
                case FIND_BY_ID -> eJson.fromJson(root, FindByIdRequest.class);
                case AGGREGATE -> parseAggregationRequest(root);
                case DELETE -> eJson.fromJson(root, DeleteRequest.class);
                case CREATE_DATABASE -> eJson.fromJson(root, CreateDatabaseRequest.class);
                case DROP_DATABASE -> eJson.fromJson(root, DropDatabaseRequest.class);
                case LIST_DATABASES -> eJson.fromJson(root, ListDatabasesRequest.class);
                case CREATE_COLLECTION -> eJson.fromJson(root, CreateCollectionRequest.class);
                case LIST_COLLECTIONS -> eJson.fromJson(root, ListCollectionsRequest.class);
                case DROP_COLLECTION -> eJson.fromJson(root, DropCollectionRequest.class);
                case CREATE_INDEX -> eJson.fromJson(root, CreateIndexRequest.class);
                case DROP_INDEX -> eJson.fromJson(root, DropIndexRequest.class);
                case REINDEX -> eJson.fromJson(root, ReindexRequest.class);
                case SAVE_SCHEMA -> eJson.fromJson(root, SaveSchemaRequest.class);
                case DELETE_SCHEMA -> eJson.fromJson(root, DeleteSchemaRequest.class);
                case CLOSE_CONNECTION -> eJson.fromJson(root, CloseConnectionRequest.class);
                case AUTHENTICATE -> eJson.fromJson(root, AuthenticateRequest.class);
                case CREATE_USER -> eJson.fromJson(root, CreateUserRequest.class);
                case DELETE_USER -> eJson.fromJson(root, DeleteUserRequest.class);
                case CHANGE_PERMISSIONS -> eJson.fromJson(root, ChangePermissionsRequest.class);
                case SET_DATABASE_OWNERS -> eJson.fromJson(root, SetDatabaseOwnersRequest.class);
                case LIST_USERS -> parseListUsersRequest(root);
                case SET_PASSWORD -> eJson.fromJson(root, SetPasswordRequest.class);
                case GET_DATABASE_STATS -> eJson.fromJson(root, GetDatabaseStatsRequest.class);
                case LISTEN -> parseListenRequest(root);
                case STOP_LISTEN -> eJson.fromJson(root, StopListenRequest.class);
                case START_TRANSACTION -> eJson.fromJson(root, StartTransactionRequest.class);
                case COMMIT_TRANSACTION -> eJson.fromJson(root, CommitTransactionRequest.class);
                case ROLLBACK_TRANSACTION -> eJson.fromJson(root, RollbackTransactionRequest.class);
                case RESOLVE_TRANSACTION -> eJson.fromJson(root, ResolveTransactionRequest.class);
                case LIST_TRANSACTIONS -> eJson.fromJson(root, ListTransactionsRequest.class);
                case LIST_SCRIPTS -> eJson.fromJson(root, ListScriptsRequest.class);
                case CANCEL_SCRIPT -> eJson.fromJson(root, CancelScriptRequest.class);
                case LIST_TRIGGER_RUNS -> eJson.fromJson(root, ListTriggerRunsRequest.class);
                case RESOLVE_TRIGGER_RUN -> eJson.fromJson(root, ResolveTriggerRunRequest.class);
                case RUN_SCRIPT -> eJson.fromJson(root, RunScriptRequest.class);
                case SAVE_PROCEDURE -> eJson.fromJson(root, SaveProcedureRequest.class);
                case DELETE_PROCEDURE -> eJson.fromJson(root, DeleteProcedureRequest.class);
                case LIST_PROCEDURES -> eJson.fromJson(root, ListProceduresRequest.class);
                case CALL_PROCEDURE -> eJson.fromJson(root, CallProcedureRequest.class);
                case SAVE_TRIGGER -> eJson.fromJson(root, SaveTriggerRequest.class);
                case DELETE_TRIGGER -> eJson.fromJson(root, DeleteTriggerRequest.class);
                case LIST_TRIGGERS -> eJson.fromJson(root, ListTriggersRequest.class);
                case TEST_TRIGGER -> eJson.fromJson(root, TestTriggerRequest.class);
                case SAVE_SCHEDULE -> eJson.fromJson(root, SaveScheduleRequest.class);
                case DELETE_SCHEDULE -> eJson.fromJson(root, DeleteScheduleRequest.class);
                case LIST_SCHEDULES -> eJson.fromJson(root, ListSchedulesRequest.class);
            };
        } catch (Exception e) {
            throw new InvalidCommandException(e);
        }
    }

    private static OperationRequest parseAggregationRequest(final JsonObject parsed) {
        final var aggRequest = eJson.fromJson(parsed, AggregateRequest.class);
        final var steps = new ArrayList<BaseAggregationStep>();
        final var roughlyParsedAggSteps = aggRequest.getAggregationSteps();
        final var jsonArray = parsed.get("aggregationSteps").asJsonArray();
        for (var i = 0; i < roughlyParsedAggSteps.size(); i++) {
            final var type = roughlyParsedAggSteps.get(i).getType();
            steps.add(parseAggregationStep(type, jsonArray, i));
        }
        aggRequest.setAggregationSteps(steps);
        return aggRequest;
    }

    private static OperationRequest parseListenRequest(final JsonObject parsed) {
        final var listenRequest = eJson.fromJson(parsed, ListenRequest.class);
        final var roughlyParsedSteps = listenRequest.getAggregationSteps();
        if (roughlyParsedSteps == null || roughlyParsedSteps.isEmpty()) {
            return listenRequest;
        }
        final var steps = new ArrayList<BaseAggregationStep>();
        final var jsonArray = parsed.get("aggregationSteps").asJsonArray();
        for (var i = 0; i < roughlyParsedSteps.size(); i++) {
            final var type = roughlyParsedSteps.get(i).getType();
            steps.add(parseAggregationStep(type, jsonArray, i));
        }
        listenRequest.setAggregationSteps(steps);
        return listenRequest;
    }

    private static BaseAggregationStep parseAggregationStep(final AggregationStepType type, final JsonArray jsonArray,
            final int index) {
        final var obj = jsonArray.get(index).asJsonObject();
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
            case REDUCE -> parseReduceStep(obj);
        };
    }

    // Hand-written rather than eJson.fromJson: the step carries a raw JsonBaseElement initial value
    // alongside the source, which the reflective path would try to bind to a concrete type.
    private static BaseAggregationStep parseReduceStep(final JsonObject obj) {
        final var script = obj.get("script").asJsonString().getValue();
        final var initialValue = obj.get("initialValue");
        final var resultField = obj.get("resultField");
        return new ReduceAggregationStep(script, initialValue,
                resultField == null || resultField.isJsonNull() ? null : resultField.asJsonString().getValue());
    }

    private static BaseAggregationStep parseMapStep(final JsonObject obj) {
        final var operators = obj.get("operators").asJsonArray();
        return new MapAggregationStep(parseMapOperators(operators));
    }

    private static List<MapOperator> parseMapOperators(final JsonArray arr) {
        final var mapOperators = new ArrayList<MapOperator>();
        for (var mapStep : arr.asList()) {
            final var mapStepObj = mapStep.asJsonObject();
            final var condition = mapStepObj.get("condition");
            BaseOperator parsedCondition = null;
            if (condition != null && !condition.isJsonNull()) {
                parsedCondition = recursiveParse(condition.asJsonObject());
            }
            final var fieldName = mapStepObj.get("fieldName").asJsonString().getValue();
            final var operator = mapStepObj.get("operator");
            MapOperator parsed;
            if (operator != null) {
                final var operatorObj = operator.asJsonObject();
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
                new ArrayParamMidOperator(midOperationType, obj.get("operands").asJsonArray());
            case ABS, SIZE -> new OneParamMidOperator(midOperationType, obj.get("operand").asJsonString().getValue());
            case CAST -> {
                final var toType = eJson.fromJson(obj.get("toType"), CastToType.class);
                if (toType == CastToType.JSON_CUSTOM) {
                    yield new CastMidOperator(obj.get("fieldName").asJsonString().getValue(),
                            obj.get("customTypeName").asJsonString().getValue());
                }
                yield new CastMidOperator(obj.get("fieldName").asJsonString().getValue(), toType);
            }
            case SCRIPT -> new ScriptMidOperator(obj.get("script").asJsonString().getValue());
        };
    }

    private static BaseAggregationStep parseFilterStep(final JsonObject obj) {
        final var operator = obj.get("operator").asJsonObject();
        return new FilterAggregationStep(recursiveParse(operator));
    }

    private static BaseOperator recursiveParse(final JsonObject operator) {
        BaseOperator parsedOperator;
        if (operator.has("fieldOperatorType")) {
            final var fieldName = operator.get("field").asJsonString().getValue();
            final var fieldValue = operator.get("value");
            final var operatorType = eJson.fromJson(operator.get("fieldOperatorType"), FieldOperatorType.class);
            parsedOperator = new FieldOperator(operatorType, fieldName, fieldValue);
        } else if (operator.has("script")) {
            // Checked before the conjunction fallback, or a malformed script operator is read as one.
            parsedOperator = new ScriptOperator(operator.get("script").asJsonString().getValue());
        } else if (operator.has("customOperatorName")) {
            final var customOperatorName = operator.get("customOperatorName").asJsonString().getValue();
            final var fieldName = operator.get("field").asJsonString().getValue();
            final var fieldValue = operator.get("value");
            parsedOperator = new CustomOperator(customOperatorName, fieldName, fieldValue, operator);
        } else {
            final var conjunctionType = eJson.fromJson(operator.get("conjunctionType"), ConjunctionOperatorType.class);
            final var operators = operator.get("operators").asJsonArray().asList().stream().map(element -> {
                try {
                    return recursiveParse(element.asJsonObject());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();
            parsedOperator = new ConjunctionOperator(conjunctionType, operators);
        }
        return parsedOperator;
    }

    private static ListUsersRequest parseListUsersRequest(final JsonObject parsed) {
        final var req = eJson.fromJson(parsed, ListUsersRequest.class);
        final var stepsEl = parsed.get("aggregationSteps");
        if (stepsEl == null || stepsEl.isJsonNull()) {
            return req;
        }
        final var jsonArray = stepsEl.asJsonArray();
        if (jsonArray.asList().isEmpty()) {
            return req;
        }
        final var roughSteps = req.getAggregationSteps();
        if (roughSteps == null || roughSteps.isEmpty()) {
            return req;
        }
        final var steps = new ArrayList<BaseAggregationStep>();
        for (var i = 0; i < roughSteps.size(); i++) {
            steps.add(parseAggregationStep(roughSteps.get(i).getType(), jsonArray, i));
        }
        req.setAggregationSteps(steps);
        return req;
    }
}
