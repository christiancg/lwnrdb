package org.techhouse.ops.req.validations;

import java.nio.charset.StandardCharsets;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.custom_types.GeoDistanceComparator;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.BaseMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastToType;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.ScriptMidOperator;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.operators.ScriptOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperationType;

public class AggregationStepValidator {

    public static ValidationResult validate(BaseAggregationStep step) {
        return switch (step.getType()) {
            case FILTER -> validateFilter((FilterAggregationStep) step);
            case MAP -> validateMap((MapAggregationStep) step);
            case GROUP_BY -> validateGroupBy((GroupByAggregationStep) step);
            case JOIN -> validateJoin((JoinAggregationStep) step);
            case COUNT, DISTINCT -> ValidationResult.ok();
            case LIMIT -> validateLimit((LimitAggregationStep) step);
            case SKIP -> validateSkip((SkipAggregationStep) step);
            case SORT -> validateSort((SortAggregationStep) step);
            case REDUCE -> validateReduce((ReduceAggregationStep) step);
        };
    }

    private static ValidationResult validateReduce(ReduceAggregationStep step) {
        if (step.getResultField() == null || step.getResultField().isBlank()) {
            return ValidationResult.fail("REDUCE step requires a non-blank resultField");
        }
        return validateScriptSource(step.getScript(), "REDUCE step");
    }

    // The gate for every script inside a pipeline: the same master switch RUN_SCRIPT sits behind, then
    // the source itself. Who may run one is decided separately, by AuthorizationChecker.
    static ValidationResult validateScriptSource(String source, String what) {
        if (!Configuration.getInstance().isScriptsEnabled()) {
            return ValidationResult.fail(ErrorCode.SCRIPTS_DISABLED, ErrorCode.SCRIPTS_DISABLED.getDefaultMessage());
        }
        if (source == null || source.isBlank()) {
            return ValidationResult.fail(what + " requires a non-blank script");
        }
        final var maxBytes = Configuration.getInstance().getAggregationScriptMaxSourceBytes();
        if (source.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            return ValidationResult.fail(ErrorCode.SCRIPT_TOO_LARGE, ErrorCode.SCRIPT_TOO_LARGE.getDefaultMessage());
        }
        return ValidationResult.ok();
    }

    // True when any step (or any operator nested in one) carries a script, so a caller that has to treat
    // a scripted pipeline differently - LISTEN refusing one, AuthorizationChecker demanding the script
    // grant for one - can ask once rather than re-walking the tree itself.
    public static boolean containsScript(java.util.List<BaseAggregationStep> steps) {
        if (steps == null) {
            return false;
        }
        for (final var step : steps) {
            final var carries = switch (step.getType()) {
                case REDUCE -> true;
                case FILTER -> operatorContainsScript(((FilterAggregationStep) step).getOperator());
                case MAP -> mapContainsScript((MapAggregationStep) step);
                default -> false;
            };
            if (carries) {
                return true;
            }
        }
        return false;
    }

    private static boolean mapContainsScript(MapAggregationStep step) {
        if (step.getOperators() == null) {
            return false;
        }
        for (final var operator : step.getOperators()) {
            if (operatorContainsScript(operator.getCondition())) {
                return true;
            }
            if (operator.getType() == MapOperationType.ADD_FIELD
                    && ((AddFieldMapOperator) operator).getOperator() instanceof ScriptMidOperator) {
                return true;
            }
        }
        return false;
    }

    private static boolean operatorContainsScript(BaseOperator operator) {
        if (operator == null) {
            return false;
        }
        if (operator.getType() == OperatorType.SCRIPT) {
            return true;
        }
        if (operator.getType() != OperatorType.CONJUNCTION) {
            return false;
        }
        final var nested = ((ConjunctionOperator) operator).getOperators();
        if (nested == null) {
            return false;
        }
        for (final var child : nested) {
            if (operatorContainsScript(child)) {
                return true;
            }
        }
        return false;
    }

    private static ValidationResult validateFilter(FilterAggregationStep step) {
        if (step.getOperator() == null) {
            return ValidationResult.fail("FILTER step requires an operator");
        }
        return validateOperator(step.getOperator());
    }

    private static ValidationResult validateMap(MapAggregationStep step) {
        if (step.getOperators() == null || step.getOperators().isEmpty()) {
            return ValidationResult.fail("MAP step requires at least one operator");
        }
        for (var op : step.getOperators()) {
            if (op.getFieldName() == null || op.getFieldName().isBlank()) {
                return ValidationResult.fail("MAP operator requires a non-blank fieldName");
            }
            if (op.getCondition() != null) {
                final var conditionResult = validateOperator(op.getCondition());
                if (!conditionResult.isValid()) {
                    return conditionResult;
                }
            }
            if (op.getType() == MapOperationType.ADD_FIELD) {
                final var addOp = (AddFieldMapOperator) op;
                if (addOp.getOperator() == null) {
                    return ValidationResult.fail("MAP ADD_FIELD operator requires a mid-operator");
                }
                final var midResult = validateMidOperator(addOp.getOperator());
                if (!midResult.isValid()) {
                    return midResult;
                }
            }
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateGroupBy(GroupByAggregationStep step) {
        if (step.getFieldName() == null || step.getFieldName().isBlank()) {
            return ValidationResult.fail("GROUP_BY step requires a non-blank fieldName");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateJoin(JoinAggregationStep step) {
        if (step.getJoinCollection() == null || step.getJoinCollection().isBlank()) {
            return ValidationResult.fail("JOIN step requires a non-blank joinCollection");
        }
        if (!step.getJoinCollection().matches(RequestValidator.NAME_PATTERN)) {
            return ValidationResult
                    .fail("JOIN joinCollection name must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        if (step.getLocalField() == null || step.getLocalField().isBlank()) {
            return ValidationResult.fail("JOIN step requires a non-blank localField");
        }
        if (step.getRemoteField() == null || step.getRemoteField().isBlank()) {
            return ValidationResult.fail("JOIN step requires a non-blank remoteField");
        }
        if (step.getAsField() == null || step.getAsField().isBlank()) {
            return ValidationResult.fail("JOIN step requires a non-blank asField");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateLimit(LimitAggregationStep step) {
        if (step.getLimit() == null) {
            return ValidationResult.fail("LIMIT step requires a limit value");
        }
        if (step.getLimit() <= 0) {
            return ValidationResult.fail("LIMIT step requires a limit greater than 0");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateSkip(SkipAggregationStep step) {
        if (step.getSkip() == null) {
            return ValidationResult.fail("SKIP step requires a skip value");
        }
        if (step.getSkip() < 0) {
            return ValidationResult.fail("SKIP step requires a skip value of 0 or greater");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateSort(SortAggregationStep step) {
        if (step.getFieldName() == null || step.getFieldName().isBlank()) {
            return ValidationResult.fail("SORT step requires a non-blank fieldName");
        }
        if (step.getAscending() == null) {
            return ValidationResult.fail("SORT step requires an ascending value");
        }
        return ValidationResult.ok();
    }

    public static ValidationResult validateOperator(BaseOperator operator) {
        if (operator.getType() == OperatorType.FIELD) {
            final var fieldOp = (FieldOperator) operator;
            if (fieldOp.getField() == null || fieldOp.getField().isBlank()) {
                return ValidationResult.fail("Field operator requires a non-blank field name");
            }
            if (fieldOp.getFieldOperatorType() == null) {
                return ValidationResult.fail("Field operator requires a fieldOperatorType");
            }
        } else if (operator.getType() == OperatorType.CUSTOM) {
            return validateCustomOperator((CustomOperator) operator);
        } else if (operator.getType() == OperatorType.SCRIPT) {
            return validateScriptSource(((ScriptOperator) operator).getSource(), "Script operator");
        } else {
            final var conjOp = (ConjunctionOperator) operator;
            if (conjOp.getConjunctionType() == null) {
                return ValidationResult.fail("Conjunction operator requires a conjunctionType");
            }
            if (conjOp.getOperators() == null || conjOp.getOperators().isEmpty()) {
                return ValidationResult.fail("Conjunction operator requires at least one nested operator");
            }
            for (var nested : conjOp.getOperators()) {
                final var result = validateOperator(nested);
                if (!result.isValid()) {
                    return result;
                }
            }
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateCustomOperator(CustomOperator operator) {
        if (operator.getField() == null || operator.getField().isBlank()) {
            return ValidationResult.fail("Custom operator requires a non-blank field name");
        }
        final var name = operator.getCustomOperatorName();
        if (name == null || name.isBlank()) {
            return ValidationResult.fail("Custom operator requires a customOperatorName");
        }
        if (!CustomTypeFactory.isKnownCustomOperator(name)) {
            return ValidationResult.fail("Unknown custom operator: " + name);
        }
        final var args = operator.getArgs();
        return switch (name) {
            case JsonGeo.OPERATOR_DISTANCE -> validateDistanceArgs(operator, args);
            case JsonGeo.OPERATOR_WITHIN -> validateWithinArgs(args);
            case JsonVector.OPERATOR_NEAREST -> validateNearestArgs(operator, args);
            default -> ValidationResult.ok();
        };
    }

    private static ValidationResult validateNearestArgs(CustomOperator operator, JsonObject args) {
        if (isNotVector(operator.getValue())) {
            return ValidationResult.fail("nearest operator requires a vector value");
        }
        final var k = args.get("k");
        if (k == null || !k.isJsonNumber() || k.asJsonNumber().getValue().intValue() <= 0) {
            return ValidationResult.fail("nearest operator requires a positive integer k");
        }
        final var exact = args.get("exact");
        if (exact != null && !exact.isJsonBoolean()) {
            return ValidationResult.fail("nearest operator exact flag must be a boolean");
        }
        return ValidationResult.ok();
    }

    private static boolean isNotVector(JsonBaseElement element) {
        return element == null || !element.isJsonCustom()
                || !JsonVector.CUSTOM_TYPE_NAME.equals(element.asJsonCustom().getCustomTypeName());
    }

    private static ValidationResult validateDistanceArgs(CustomOperator operator, JsonObject args) {
        if (isNotGeo(operator.getValue())) {
            return ValidationResult.fail("distance operator requires a geo value");
        }
        final var comparator = args.get("comparator");
        if (comparator == null || !comparator.isJsonString() || parseComparator(comparator) == null) {
            return ValidationResult.fail("distance operator requires a valid comparator");
        }
        final var distance = args.get("distance");
        if (distance == null || !distance.isJsonNumber()) {
            return ValidationResult.fail("distance operator requires a numeric distance");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateWithinArgs(JsonObject args) {
        final var polygon = args.get("polygon");
        if (polygon == null || !polygon.isJsonArray() || polygon.asJsonArray().size() < 3) {
            return ValidationResult.fail("within operator requires a polygon of at least 3 points");
        }
        for (var vertex : polygon.asJsonArray().asList()) {
            if (isNotGeo(vertex)) {
                return ValidationResult.fail("within operator requires a polygon of geo points");
            }
        }
        return ValidationResult.ok();
    }

    private static boolean isNotGeo(JsonBaseElement element) {
        return element == null || !element.isJsonCustom()
                || !JsonGeo.CUSTOM_TYPE_NAME.equals(element.asJsonCustom().getCustomTypeName());
    }

    // Parses a comparator element to a GeoDistanceComparator, or null when it is not a valid comparator.
    private static GeoDistanceComparator parseComparator(JsonBaseElement comparator) {
        try {
            return GeoDistanceComparator.valueOf(comparator.asJsonString().getValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static ValidationResult validateMidOperator(BaseMidOperator midOperator) {
        return switch (midOperator.getType()) {
            case AVG, SUM, MAX, MIN, MULTIPLY, CONCAT -> {
                final var op = (ArrayParamMidOperator) midOperator;
                if (op.getOperands() == null || op.getOperands().asList().isEmpty()) {
                    yield ValidationResult.fail(midOperator.getType() + " operator requires at least one operand");
                }
                yield ValidationResult.ok();
            }
            case SUBS, DIVIDE, POW, ROOT -> {
                final var op = (ArrayParamMidOperator) midOperator;
                if (op.getOperands() == null || op.getOperands().asList().size() < 2) {
                    yield ValidationResult.fail(midOperator.getType() + " operator requires at least two operands");
                }
                yield ValidationResult.ok();
            }
            case ABS, SIZE -> {
                final var op = (OneParamMidOperator) midOperator;
                if (op.getOperand() == null || op.getOperand().isBlank()) {
                    yield ValidationResult.fail(midOperator.getType() + " operator requires a non-blank operand");
                }
                yield ValidationResult.ok();
            }
            case SCRIPT -> validateScriptSource(((ScriptMidOperator) midOperator).getSource(), "SCRIPT operator");
            case CAST -> {
                final var op = (CastMidOperator) midOperator;
                if (op.getFieldName() == null || op.getFieldName().isBlank()) {
                    yield ValidationResult.fail("CAST operator requires a non-blank fieldName");
                }
                if (op.getToType() == null) {
                    yield ValidationResult.fail("CAST operator requires a toType");
                }
                if (op.getToType() == CastToType.JSON_CUSTOM
                        && (op.getCustomTypeName() == null || op.getCustomTypeName().isBlank())) {
                    yield ValidationResult.fail("CAST to JSON_CUSTOM requires a non-blank customTypeName");
                }
                yield ValidationResult.ok();
            }
        };
    }
}
