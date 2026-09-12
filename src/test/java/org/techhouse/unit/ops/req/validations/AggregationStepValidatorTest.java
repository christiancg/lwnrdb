package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.ops.req.validations.AggregationStepValidator;

public class AggregationStepValidatorTest {
    @Test
    public void validate_filterStep_nestedConjunctionValid_returnsOk() {
        final var inner1 = new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"));
        final var inner2 = new FieldOperator(FieldOperatorType.GREATER_THAN, "age", new JsonString("18"));
        final var op = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of(inner1, inner2));
        assertTrue(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_filterStep_nestedConjunctionWithInvalidChild_returnsFail() {
        final var invalidInner = new FieldOperator(FieldOperatorType.EQUALS, null, new JsonString("x"));
        final var op = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of(invalidInner));
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_mapStep_validAddField_returnsOk() {
        final var operands = new JsonArray();
        operands.add(new JsonString("field1"));
        final var midOp = new ArrayParamMidOperator(MidOperationType.SUM, operands);
        final var mapOp = new AddFieldMapOperator("result", null, midOp);
        assertTrue(AggregationStepValidator.validate(new MapAggregationStep(List.of(mapOp))).isValid());
    }

    @Test
    public void validate_mapStep_validRemoveField_returnsOk() {
        final var mapOp = new RemoveFieldMapOperator("fieldToRemove", null);
        assertTrue(AggregationStepValidator.validate(new MapAggregationStep(List.of(mapOp))).isValid());
    }

    @Test
    public void validate_mapStep_blankFieldName_returnsFail() {
        final var operands = new JsonArray();
        operands.add(new JsonString("x"));
        final var midOp = new ArrayParamMidOperator(MidOperationType.SUM, operands);
        final var mapOp = new AddFieldMapOperator("  ", null, midOp);
        assertFalse(AggregationStepValidator.validate(new MapAggregationStep(List.of(mapOp))).isValid());
    }

    @Test
    public void validate_mapStep_nullFieldName_returnsFail() {
        final var operands = new JsonArray();
        operands.add(new JsonString("x"));
        final var midOp = new ArrayParamMidOperator(MidOperationType.SUM, operands);
        final var mapOp = new AddFieldMapOperator(null, null, midOp);
        assertFalse(AggregationStepValidator.validate(new MapAggregationStep(List.of(mapOp))).isValid());
    }

    @Test
    public void validate_mapStep_withValidCondition_returnsOk() {
        final var condition = new FieldOperator(FieldOperatorType.EQUALS, "flag", new JsonString("true"));
        final var operands = new JsonArray();
        operands.add(new JsonString("x"));
        final var midOp = new ArrayParamMidOperator(MidOperationType.SUM, operands);
        final var mapOp = new AddFieldMapOperator("result", condition, midOp);
        assertTrue(AggregationStepValidator.validate(new MapAggregationStep(List.of(mapOp))).isValid());
    }

    @Test
    public void validate_mapStep_withInvalidCondition_returnsFail() {
        final var condition = new FieldOperator(FieldOperatorType.EQUALS, null, new JsonString("true"));
        final var operands = new JsonArray();
        operands.add(new JsonString("x"));
        final var midOp = new ArrayParamMidOperator(MidOperationType.SUM, operands);
        final var mapOp = new AddFieldMapOperator("result", condition, midOp);
        assertFalse(AggregationStepValidator.validate(new MapAggregationStep(List.of(mapOp))).isValid());
    }

    @Test
    public void validate_groupByStep_validFieldName_returnsOk() {
        assertTrue(AggregationStepValidator.validate(new GroupByAggregationStep("category")).isValid());
    }

    @Test
    public void validate_groupByStep_blankFieldName_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new GroupByAggregationStep("  ")).isValid());
    }

    @Test
    public void validate_groupByStep_nullFieldName_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new GroupByAggregationStep(null)).isValid());
    }

    @Test
    public void validate_joinStep_allFieldsPresent_returnsOk() {
        assertTrue(AggregationStepValidator
                .validate(new JoinAggregationStep("other_coll", "localId", "remoteId", "joined")).isValid());
    }

    @Test
    public void validate_joinStep_nullJoinCollection_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new JoinAggregationStep(null, "localId", "remoteId", "joined"))
                .isValid());
    }

    @Test
    public void validate_joinStep_invalidCollectionName_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new JoinAggregationStep("ab", "localId", "remoteId", "joined"))
                .isValid());
    }

    @Test
    public void validate_joinStep_blankLocalField_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new JoinAggregationStep("other_coll", "  ", "remoteId", "joined"))
                .isValid());
    }

    @Test
    public void validate_joinStep_blankRemoteField_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new JoinAggregationStep("other_coll", "localId", "", "joined"))
                .isValid());
    }

    @Test
    public void validate_joinStep_blankAsField_returnsFail() {
        assertFalse(AggregationStepValidator
                .validate(new JoinAggregationStep("other_coll", "localId", "remoteId", null)).isValid());
    }

    @Test
    public void validate_countStep_returnsOk() {
        assertTrue(AggregationStepValidator.validate(new CountAggregationStep()).isValid());
    }

    @Test
    public void validate_distinctStep_withFieldName_returnsOk() {
        assertTrue(AggregationStepValidator.validate(new DistinctAggregationStep("name")).isValid());
    }

    @Test
    public void validate_distinctStep_withNullFieldName_returnsOk() {
        assertTrue(AggregationStepValidator.validate(new DistinctAggregationStep(null)).isValid());
    }

    @Test
    public void validate_distinctStep_withBlankFieldName_returnsOk() {
        assertTrue(AggregationStepValidator.validate(new DistinctAggregationStep("  ")).isValid());
    }

    @Test
    public void validate_limitStep_positiveLimit_returnsOk() {
        assertTrue(AggregationStepValidator.validate(new LimitAggregationStep(10)).isValid());
    }

    @Test
    public void validate_limitStep_maxIntLimit_returnsOk() {
        assertTrue(AggregationStepValidator.validate(new LimitAggregationStep(Integer.MAX_VALUE)).isValid());
    }

    @Test
    public void validate_limitStep_zeroLimit_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new LimitAggregationStep(0)).isValid());
    }

    @Test
    public void validate_limitStep_negativeLimit_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new LimitAggregationStep(-1)).isValid());
    }

    @Test
    public void validate_limitStep_nullLimit_returnsFail() {
        final var step = new LimitAggregationStep(1);
        step.setLimit(null);
        assertFalse(AggregationStepValidator.validate(step).isValid());
    }

    @Test
    public void validate_skipStep_zeroSkip_returnsOk() {
        assertTrue(AggregationStepValidator.validate(new SkipAggregationStep(0)).isValid());
    }

    @Test
    public void validate_skipStep_positiveSkip_returnsOk() {
        assertTrue(AggregationStepValidator.validate(new SkipAggregationStep(5)).isValid());
    }

    @Test
    public void validate_skipStep_negativeSkip_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new SkipAggregationStep(-1)).isValid());
    }

    @Test
    public void validate_skipStep_nullSkip_returnsFail() {
        final var step = new SkipAggregationStep(1);
        step.setSkip(null);
        assertFalse(AggregationStepValidator.validate(step).isValid());
    }

    @Test
    public void validate_sortStep_valid_returnsOk() {
        assertTrue(AggregationStepValidator.validate(new SortAggregationStep("score", true)).isValid());
    }

    @Test
    public void validate_sortStep_blankFieldName_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new SortAggregationStep("  ", true)).isValid());
    }

    @Test
    public void validate_sortStep_nullFieldName_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new SortAggregationStep(null, false)).isValid());
    }

    @Test
    public void validate_sortStep_nullAscending_returnsFail() {
        final var step = new SortAggregationStep("score", true);
        step.setAscending(null);
        assertFalse(AggregationStepValidator.validate(step).isValid());
    }
}
