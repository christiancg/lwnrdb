package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastToType;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.validations.AggregationStepValidator;

public class AggregationOperatorValidatorTest {
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
    public void validate_customOperator_validDistance_returnsOk() {
        new org.techhouse.ejson.EJson(); // register geo custom type
        final var op = distanceOperator("location");
        assertTrue(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_customOperator_validWithin_returnsOk() {
        new org.techhouse.ejson.EJson();
        final var op = withinOperator(3);
        assertTrue(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_customOperator_blankField_returnsFail() {
        new org.techhouse.ejson.EJson();
        final var op = distanceOperator("  ");
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_customOperator_unknownOperator_returnsFail() {
        new org.techhouse.ejson.EJson();
        final var args = new org.techhouse.ejson.elements.JsonObject();
        final var op = new org.techhouse.ops.req.agg.operators.CustomOperator("nope", "location", null, args);
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_customOperator_distanceMissingComparator_returnsFail() {
        new org.techhouse.ejson.EJson();
        final var args = new org.techhouse.ejson.elements.JsonObject();
        args.add("value", new org.techhouse.ejson.custom_types.JsonGeo("#geo(40.0,-74.0)"));
        args.add("distance", new org.techhouse.ejson.elements.JsonNumber(1000));
        final var op = new org.techhouse.ops.req.agg.operators.CustomOperator("distance", "location",
                new org.techhouse.ejson.custom_types.JsonGeo("#geo(40.0,-74.0)"), args);
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_customOperator_distanceNonGeoValue_returnsFail() {
        new org.techhouse.ejson.EJson();
        final var args = new org.techhouse.ejson.elements.JsonObject();
        args.add("comparator", new JsonString("SMALLER_THAN"));
        args.add("distance", new org.techhouse.ejson.elements.JsonNumber(1000));
        final var op = new org.techhouse.ops.req.agg.operators.CustomOperator("distance", "location",
                new JsonString("not a geo"), args);
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_customOperator_withinTooFewPoints_returnsFail() {
        new org.techhouse.ejson.EJson();
        final var op = withinOperator(2);
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_customOperator_validNearest_returnsOk() {
        new org.techhouse.ejson.EJson();
        final var op = nearestOperator(5, null);
        assertTrue(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_customOperator_nearestNonVectorValue_returnsFail() {
        new org.techhouse.ejson.EJson();
        final var args = new org.techhouse.ejson.elements.JsonObject();
        args.add("k", new org.techhouse.ejson.elements.JsonNumber(5));
        final var op = new org.techhouse.ops.req.agg.operators.CustomOperator("nearest", "embedding",
                new JsonString("not a vector"), args);
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_customOperator_nearestNonPositiveK_returnsFail() {
        new org.techhouse.ejson.EJson();
        final var op = nearestOperator(0, null);
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_customOperator_nearestMissingK_returnsFail() {
        new org.techhouse.ejson.EJson();
        final var target = new org.techhouse.ejson.custom_types.JsonVector("#vector(1.0,0.0)");
        final var args = new org.techhouse.ejson.elements.JsonObject();
        args.add("value", target);
        final var op = new org.techhouse.ops.req.agg.operators.CustomOperator("nearest", "embedding", target, args);
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    @Test
    public void validate_customOperator_nearestNonBooleanExact_returnsFail() {
        new org.techhouse.ejson.EJson();
        final var op = nearestOperator(5, new JsonString("yes"));
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(op)).isValid());
    }

    private static org.techhouse.ops.req.agg.operators.CustomOperator nearestOperator(int k, JsonBaseElement exact) {
        final var target = new org.techhouse.ejson.custom_types.JsonVector("#vector(1.0,0.0)");
        final var args = new org.techhouse.ejson.elements.JsonObject();
        args.add("value", target);
        args.add("k", new org.techhouse.ejson.elements.JsonNumber((double) k));
        if (exact != null) {
            args.add("exact", exact);
        }
        return new org.techhouse.ops.req.agg.operators.CustomOperator("nearest", "embedding", target, args);
    }

    private static org.techhouse.ops.req.agg.operators.CustomOperator distanceOperator(String field) {
        final var target = new org.techhouse.ejson.custom_types.JsonGeo("#geo(40.71,-74.0)");
        final var args = new org.techhouse.ejson.elements.JsonObject();
        args.add("value", target);
        args.add("comparator", new JsonString("SMALLER_THAN"));
        args.add("distance", new org.techhouse.ejson.elements.JsonNumber((double) 1000));
        return new org.techhouse.ops.req.agg.operators.CustomOperator("distance", field, target, args);
    }

    private static org.techhouse.ops.req.agg.operators.CustomOperator withinOperator(int points) {
        final var polygon = new JsonArray();
        for (var i = 0; i < points; i++) {
            polygon.add(new org.techhouse.ejson.custom_types.JsonGeo("#geo(" + i + ",0)"));
        }
        final var args = new org.techhouse.ejson.elements.JsonObject();
        args.add("polygon", polygon);
        return new org.techhouse.ops.req.agg.operators.CustomOperator("within", "location", null, args);
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
    public void validate_mapStep_nullOperators_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new MapAggregationStep(null)).isValid());
    }

    @Test
    public void validate_mapStep_emptyOperators_returnsFail() {
        assertFalse(AggregationStepValidator.validate(new MapAggregationStep(List.of())).isValid());
    }

    @Test
    public void validate_mapStep_addFieldNullMidOperator_returnsFail() {
        final var mapOp = new AddFieldMapOperator("result", null, null);
        assertFalse(AggregationStepValidator.validate(new MapAggregationStep(List.of(mapOp))).isValid());
    }

    @Test
    public void validate_arrayParamMidOperator_sufficientOperands_returnsOk() {
        final var operands = new JsonArray();
        operands.add(new JsonString("field1"));
        operands.add(new JsonString("field2"));
        assertTrue(AggregationStepValidator
                .validateMidOperator(new ArrayParamMidOperator(MidOperationType.SUM, operands)).isValid());
    }

    @Test
    public void validate_arrayParamMidOperator_emptyOperands_returnsFail() {
        assertFalse(AggregationStepValidator
                .validateMidOperator(new ArrayParamMidOperator(MidOperationType.SUM, new JsonArray())).isValid());
    }

    @Test
    public void validate_arrayParamMidOperator_nullOperands_returnsFail() {
        assertFalse(AggregationStepValidator.validateMidOperator(new ArrayParamMidOperator(MidOperationType.AVG, null))
                .isValid());
    }

    @Test
    public void validate_arrayParamMidOperator_divideOneOperand_returnsFail() {
        final var operands = new JsonArray();
        operands.add(new JsonString("field1"));
        assertFalse(AggregationStepValidator
                .validateMidOperator(new ArrayParamMidOperator(MidOperationType.DIVIDE, operands)).isValid());
    }

    @Test
    public void validate_arrayParamMidOperator_subtractTwoOperands_returnsOk() {
        final var operands = new JsonArray();
        operands.add(new JsonString("a"));
        operands.add(new JsonString("b"));
        assertTrue(AggregationStepValidator
                .validateMidOperator(new ArrayParamMidOperator(MidOperationType.SUBS, operands)).isValid());
    }

    @Test
    public void validate_arrayParamMidOperator_powOneOperand_returnsFail() {
        final var operands = new JsonArray();
        operands.add(new JsonString("base"));
        assertFalse(AggregationStepValidator
                .validateMidOperator(new ArrayParamMidOperator(MidOperationType.POW, operands)).isValid());
    }

    @Test
    public void validate_arrayParamMidOperator_rootOneOperand_returnsFail() {
        final var operands = new JsonArray();
        operands.add(new JsonString("base"));
        assertFalse(AggregationStepValidator
                .validateMidOperator(new ArrayParamMidOperator(MidOperationType.ROOT, operands)).isValid());
    }

    @Test
    public void validate_oneParamMidOperator_validOperand_returnsOk() {
        assertTrue(AggregationStepValidator
                .validateMidOperator(new OneParamMidOperator(MidOperationType.ABS, "myField")).isValid());
    }

    @Test
    public void validate_oneParamMidOperator_blankOperand_returnsFail() {
        assertFalse(AggregationStepValidator.validateMidOperator(new OneParamMidOperator(MidOperationType.SIZE, "  "))
                .isValid());
    }

    @Test
    public void validate_oneParamMidOperator_nullOperand_returnsFail() {
        assertFalse(AggregationStepValidator.validateMidOperator(new OneParamMidOperator(MidOperationType.ABS, null))
                .isValid());
    }

    @Test
    public void validate_castMidOperator_valid_returnsOk() {
        assertTrue(AggregationStepValidator.validateMidOperator(new CastMidOperator("score", CastToType.STRING))
                .isValid());
    }

    @Test
    public void validate_castMidOperator_blankFieldName_returnsFail() {
        assertFalse(
                AggregationStepValidator.validateMidOperator(new CastMidOperator("  ", CastToType.NUMBER)).isValid());
    }

    @Test
    public void validate_castMidOperator_nullFieldName_returnsFail() {
        assertFalse(
                AggregationStepValidator.validateMidOperator(new CastMidOperator(null, CastToType.BOOLEAN)).isValid());
    }

    @Test
    public void validate_castMidOperator_nullToType_returnsFail() {
        assertFalse(AggregationStepValidator.validateMidOperator(new CastMidOperator("score", (CastToType) null))
                .isValid());
    }

    @Test
    public void validate_castMidOperator_jsonCustom_missingTypeName_returnsFail() {
        final var op = new CastMidOperator("score", CastToType.JSON_CUSTOM);
        assertFalse(AggregationStepValidator.validateMidOperator(op).isValid());
    }

    @Test
    public void validate_castMidOperator_jsonCustom_withTypeName_returnsOk() {
        assertTrue(AggregationStepValidator.validateMidOperator(new CastMidOperator("score", "datetime")).isValid());
    }

    @Test
    public void validate_maxOperator_withOperands_returnsOk() {
        final var operands = new JsonArray();
        operands.add(new JsonString("a"));
        assertTrue(AggregationStepValidator
                .validateMidOperator(new ArrayParamMidOperator(MidOperationType.MAX, operands)).isValid());
    }

    @Test
    public void validate_minOperator_emptyOperands_returnsFail() {
        assertFalse(AggregationStepValidator
                .validateMidOperator(new ArrayParamMidOperator(MidOperationType.MIN, new JsonArray())).isValid());
    }

    @Test
    public void validate_multiplyOperator_withOperands_returnsOk() {
        final var operands = new JsonArray();
        operands.add(new JsonString("a"));
        assertTrue(AggregationStepValidator
                .validateMidOperator(new ArrayParamMidOperator(MidOperationType.MULTIPLY, operands)).isValid());
    }

    @Test
    public void validate_concatOperator_withOperands_returnsOk() {
        final var operands = new JsonArray();
        operands.add(new JsonString("hello"));
        assertTrue(AggregationStepValidator
                .validateMidOperator(new ArrayParamMidOperator(MidOperationType.CONCAT, operands)).isValid());
    }
}
