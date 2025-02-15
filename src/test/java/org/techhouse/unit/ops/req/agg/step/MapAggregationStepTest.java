package org.techhouse.unit.ops.req.agg.step;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.map.MapOperationType;
import org.techhouse.ops.req.agg.step.map.MapOperator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MapAggregationStepTest {
    // Create MapAggregationStep with valid list of MapOperators
    @Test
    public void create_map_aggregation_step_with_valid_operators() {
        List<MapOperator> operators = List.of(
            new MapOperator(MapOperationType.ADD_FIELD, "field1", null),
            new MapOperator(MapOperationType.REMOVE_FIELD, "field2", null)
        );

        MapAggregationStep step = new MapAggregationStep(operators);

        assertEquals(AggregationStepType.MAP, step.getType());
        assertEquals(operators, step.getOperators());
        assertEquals(2, step.getOperators().size());
    }

    // Create MapAggregationStep with empty operators list
    @Test
    public void create_map_aggregation_step_with_empty_operators() {
        List<MapOperator> operators = List.of();

        MapAggregationStep step = new MapAggregationStep(operators);

        assertEquals(AggregationStepType.MAP, step.getType());
        assertNotNull(step.getOperators());
        assertTrue(step.getOperators().isEmpty());
    }

    // test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        List<MapOperator> operators = List.of(
                new MapOperator(MapOperationType.ADD_FIELD, "field1", null),
                new MapOperator(MapOperationType.ADD_FIELD, "field2", null)
        );

        MapAggregationStep step = new MapAggregationStep(operators);

        // Test getter
        assertEquals(operators, step.getOperators());

        // Test setter
        List<MapOperator> newOperators = List.of(
                new MapOperator(MapOperationType.REMOVE_FIELD, "field3", null)
        );
        step.setOperators(newOperators);
        assertEquals(newOperators, step.getOperators());
    }
}