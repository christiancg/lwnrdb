package org.techhouse.unit.ops.req.agg.step;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.map.MapOperationType;
import org.techhouse.ops.req.agg.step.map.MapOperator;

public class MapAggregationStepTest {
    @Test
    public void create_map_aggregation_step_with_valid_operators() {
        List<MapOperator> operators = List.of(new MapOperator(MapOperationType.ADD_FIELD, "field1", null),
                new MapOperator(MapOperationType.REMOVE_FIELD, "field2", null));

        MapAggregationStep step = new MapAggregationStep(operators);

        assertEquals(AggregationStepType.MAP, step.getType());
        assertEquals(operators, step.getOperators());
        assertEquals(2, step.getOperators().size());
    }

    @Test
    public void create_map_aggregation_step_with_empty_operators() {
        List<MapOperator> operators = List.of();

        MapAggregationStep step = new MapAggregationStep(operators);

        assertEquals(AggregationStepType.MAP, step.getType());
        assertNotNull(step.getOperators());
        assertTrue(step.getOperators().isEmpty());
    }

    @Test
    public void test_getters_and_setters() {
        List<MapOperator> operators = List.of(new MapOperator(MapOperationType.ADD_FIELD, "field1", null),
                new MapOperator(MapOperationType.ADD_FIELD, "field2", null));

        MapAggregationStep step = new MapAggregationStep(operators);

        assertEquals(operators, step.getOperators());

        List<MapOperator> newOperators = List.of(new MapOperator(MapOperationType.REMOVE_FIELD, "field3", null));
        step.setOperators(newOperators);
        assertEquals(newOperators, step.getOperators());
    }
}
