package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.validations.AggregationStepValidator;
import org.techhouse.ops.req.validations.ValidationResult;

public class GeoOperandValidatorTest {
    @BeforeAll
    static void registerCustomTypes() {
        new EJson();
    }

    private static CustomOperator within(String... vertices) {
        final var polygon = new JsonArray();
        for (final var vertex : vertices) {
            polygon.add(new JsonGeo(vertex));
        }
        final var args = new JsonObject();
        args.add("polygon", polygon);
        return new CustomOperator("within", "location", null, args);
    }

    private static CustomOperator distanceFrom(String target) {
        final var value = new JsonGeo(target);
        final var args = new JsonObject();
        args.add("value", value);
        args.add("comparator", new JsonString("SMALLER_THAN"));
        args.add("distance", new JsonNumber((double) 1000));
        return new CustomOperator("distance", "location", value, args);
    }

    private static ValidationResult filter(CustomOperator operator) {
        return AggregationStepValidator.validate(new FilterAggregationStep(operator));
    }

    @Test
    public void test_within_refuses_a_nan_vertex() {
        final var result = filter(within("#geo(0,0)", "#geo(10,0)", "#geo(NaN,NaN)"));

        assertFalse(result.isValid(), "a NaN vertex empties the index pre-filter while the scan still matches rows");
        assertTrue(result.getErrorMessage().contains("finite"));
    }

    @Test
    public void test_within_refuses_a_nan_vertex_inside_a_map_condition() {
        final var mapOp = new AddFieldMapOperator("inside", within("#geo(0,0)", "#geo(10,0)", "#geo(5,NaN)"),
                new OneParamMidOperator(MidOperationType.ABS, "score"));

        assertFalse(AggregationStepValidator.validate(new MapAggregationStep(List.of(mapOp))).isValid());
    }

    @Test
    public void test_distance_refuses_a_nan_target() {
        final var result = filter(distanceFrom("#geo(NaN,0)"));

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("finite"));
    }

    @Test
    public void test_finite_geo_operands_are_still_accepted() {
        assertTrue(filter(within("#geo(0,0)", "#geo(10,0)", "#geo(5,5)")).isValid());
        assertTrue(filter(distanceFrom("#geo(40.71,-74.0)")).isValid());
    }
}
