package org.techhouse.ops.req.validations;

import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.BaseMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastToType;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
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
        };
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
