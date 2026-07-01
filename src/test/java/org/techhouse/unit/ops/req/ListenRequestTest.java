package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;

public class ListenRequestTest {

    @Test
    public void constructor_setsTypeAndCoordinates() {
        final var req = new ListenRequest("myDb", "myColl");

        assertEquals(OperationType.LISTEN, req.getType());
        assertEquals("myDb", req.getDatabaseName());
        assertEquals("myColl", req.getCollectionName());
    }

    @Test
    public void aggregationSteps_getterSetterRoundTrip() {
        final var req = new ListenRequest("db", "coll");
        final List<BaseAggregationStep> steps = List.of(new FilterAggregationStep(null));

        req.setAggregationSteps(steps);

        assertEquals(steps, req.getAggregationSteps());
    }

    @Test
    public void aggregationSteps_defaultIsNull() {
        final var req = new ListenRequest("db", "coll");

        assertNull(req.getAggregationSteps());
    }
}
