package org.techhouse.unit.ops.req.validations;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.*;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.*;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.ops.req.validations.AggregationStepValidator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AggregationStepValidatorTest {

    // FILTER
    @Test
    public void validate_filterStep_validFieldOperator_returnsOk() {
        final var op = new FieldOperator(FieldOperatorType.EQUALS, "age", new JsonString("30"));
        assertTrue(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_filterStep_nullOperator_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(null)).isValid());
    }

    @Test
    public void validate_filterStep_fieldOperatorNullField_returnsFail() {
        final var op = new FieldOperator(FieldOperatorType.EQUALS, null, new JsonString("30"));
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_filterStep_fieldOperatorBlankField_returnsFail() {
        final var op = new FieldOperator(FieldOperatorType.EQUALS, "  ", new JsonString("30"));
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_filterStep_fieldOperatorNullType_returnsFail() {
        final var op = new FieldOperator(null, "age", new JsonString("30"));
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_filterStep_conjunctionOperatorEmptyList_returnsFail() {
        final var op = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of());
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_filterStep_conjunctionOperatorNullList_returnsFail() {
        final var op = new ConjunctionOperator(ConjunctionOperatorType.AND, null);
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_filterStep_conjunctionOperatorNullType_returnsFail() {
        final var inner = new FieldOperator(FieldOperatorType.EQUALS, "age", new JsonString("30"));
        final var op = new ConjunctionOperator(null, List.of(inner));
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

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

    // MAP
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
    public void validate_mapStep_nullOperators_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new MapAggregationStep(null)).isValid());
    }

    @Test
    public void validate_mapStep_emptyOperators_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new MapAggregationStep(List.of())).isValid());
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
    public void validate_mapStep_addFieldNullMidOperator_returnsFail() {
        final var mapOp = new AddFieldMapOperator("result", null, null);
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

    // GROUP_BY
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

    // JOIN
    @Test
    public void validate_joinStep_allFieldsPresent_returnsOk() {
        assertTrue(AggregationStepValidator.validate(
                new JoinAggregationStep("other_coll", "localId", "remoteId", "joined")).isValid());
    }

    @Test
    public void validate_joinStep_nullJoinCollection_returnsFail() {
        assertFalse(AggregationStepValidator.validate(
                new JoinAggregationStep(null, "localId", "remoteId", "joined")).isValid());
    }

    @Test
    public void validate_joinStep_invalidCollectionName_returnsFail() {
        // too short to match NAME_PATTERN
        assertFalse(AggregationStepValidator.validate(
                new JoinAggregationStep("ab", "localId", "remoteId", "joined")).isValid());
    }

    @Test
    public void validate_joinStep_blankLocalField_returnsFail() {
        assertFalse(AggregationStepValidator.validate(
                new JoinAggregationStep("other_coll", "  ", "remoteId", "joined")).isValid());
    }

    @Test
    public void validate_joinStep_blankRemoteField_returnsFail() {
        assertFalse(AggregationStepValidator.validate(
                new JoinAggregationStep("other_coll", "localId", "", "joined")).isValid());
    }

    @Test
    public void validate_joinStep_blankAsField_returnsFail() {
        assertFalse(AggregationStepValidator.validate(
                new JoinAggregationStep("other_coll", "localId", "remoteId", null)).isValid());
    }

    // COUNT
    @Test
    public void validate_countStep_returnsOk() {
        assertTrue(AggregationStepValidator.validate(new CountAggregationStep()).isValid());
    }

    // DISTINCT
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

    // LIMIT
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

    // SKIP
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

    // SORT
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

    // Mid-operator: ArrayParam (SUM - min 1 operand)
    @Test
    public void validate_arrayParamMidOperator_sufficientOperands_returnsOk() {
        final var operands = new JsonArray();
        operands.add(new JsonString("field1"));
        operands.add(new JsonString("field2"));
        assertTrue(AggregationStepValidator.validateMidOperator(
                new ArrayParamMidOperator(MidOperationType.SUM, operands)).isValid());
    }

    @Test
    public void validate_arrayParamMidOperator_emptyOperands_returnsFail() {
        assertTrue(AggregationStepValidator.validateMidOperator(
                new ArrayParamMidOperator(MidOperationType.SUM, new JsonArray())).isValid() == false);
    }

    @Test
    public void validate_arrayParamMidOperator_nullOperands_returnsFail() {
        assertFalse(AggregationStepValidator.validateMidOperator(
                new ArrayParamMidOperator(MidOperationType.AVG, null)).isValid());
    }

    // Mid-operator: binary operators require 2+ operands
    @Test
    public void validate_arrayParamMidOperator_divideOneOperand_returnsFail() {
        final var operands = new JsonArray();
        operands.add(new JsonString("field1"));
        assertFalse(AggregationStepValidator.validateMidOperator(
                new ArrayParamMidOperator(MidOperationType.DIVIDE, operands)).isValid());
    }

    @Test
    public void validate_arrayParamMidOperator_subtractTwoOperands_returnsOk() {
        final var operands = new JsonArray();
        operands.add(new JsonString("a"));
        operands.add(new JsonString("b"));
        assertTrue(AggregationStepValidator.validateMidOperator(
                new ArrayParamMidOperator(MidOperationType.SUBS, operands)).isValid());
    }

    @Test
    public void validate_arrayParamMidOperator_powOneOperand_returnsFail() {
        final var operands = new JsonArray();
        operands.add(new JsonString("base"));
        assertFalse(AggregationStepValidator.validateMidOperator(
                new ArrayParamMidOperator(MidOperationType.POW, operands)).isValid());
    }

    @Test
    public void validate_arrayParamMidOperator_rootOneOperand_returnsFail() {
        final var operands = new JsonArray();
        operands.add(new JsonString("base"));
        assertFalse(AggregationStepValidator.validateMidOperator(
                new ArrayParamMidOperator(MidOperationType.ROOT, operands)).isValid());
    }

    // Mid-operator: OneParam (ABS, SIZE)
    @Test
    public void validate_oneParamMidOperator_validOperand_returnsOk() {
        assertTrue(AggregationStepValidator.validateMidOperator(
                new OneParamMidOperator(MidOperationType.ABS, "myField")).isValid());
    }

    @Test
    public void validate_oneParamMidOperator_blankOperand_returnsFail() {
        assertFalse(AggregationStepValidator.validateMidOperator(
                new OneParamMidOperator(MidOperationType.SIZE, "  ")).isValid());
    }

    @Test
    public void validate_oneParamMidOperator_nullOperand_returnsFail() {
        assertFalse(AggregationStepValidator.validateMidOperator(
                new OneParamMidOperator(MidOperationType.ABS, null)).isValid());
    }

    // Mid-operator: CAST
    @Test
    public void validate_castMidOperator_valid_returnsOk() {
        assertTrue(AggregationStepValidator.validateMidOperator(
                new CastMidOperator("score", CastToType.STRING)).isValid());
    }

    @Test
    public void validate_castMidOperator_blankFieldName_returnsFail() {
        assertFalse(AggregationStepValidator.validateMidOperator(
                new CastMidOperator("  ", CastToType.NUMBER)).isValid());
    }

    @Test
    public void validate_castMidOperator_nullFieldName_returnsFail() {
        assertFalse(AggregationStepValidator.validateMidOperator(
                new CastMidOperator(null, CastToType.BOOLEAN)).isValid());
    }

    @Test
    public void validate_castMidOperator_nullToType_returnsFail() {
        assertFalse(AggregationStepValidator.validateMidOperator(
                new CastMidOperator("score", null)).isValid());
    }

    // Mid-operator: MAX, MIN, MULTIPLY, CONCAT
    @Test
    public void validate_maxOperator_withOperands_returnsOk() {
        final var operands = new JsonArray();
        operands.add(new JsonString("a"));
        assertTrue(AggregationStepValidator.validateMidOperator(
                new ArrayParamMidOperator(MidOperationType.MAX, operands)).isValid());
    }

    @Test
    public void validate_minOperator_emptyOperands_returnsFail() {
        assertFalse(AggregationStepValidator.validateMidOperator(
                new ArrayParamMidOperator(MidOperationType.MIN, new JsonArray())).isValid());
    }

    @Test
    public void validate_multiplyOperator_withOperands_returnsOk() {
        final var operands = new JsonArray();
        operands.add(new JsonString("a"));
        assertTrue(AggregationStepValidator.validateMidOperator(
                new ArrayParamMidOperator(MidOperationType.MULTIPLY, operands)).isValid());
    }

    @Test
    public void validate_concatOperator_withOperands_returnsOk() {
        final var operands = new JsonArray();
        operands.add(new JsonString("hello"));
        assertTrue(AggregationStepValidator.validateMidOperator(
                new ArrayParamMidOperator(MidOperationType.CONCAT, operands)).isValid());
    }
}
