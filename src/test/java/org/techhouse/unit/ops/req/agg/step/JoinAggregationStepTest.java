package org.techhouse.unit.ops.req.agg.step;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;

public class JoinAggregationStepTest {
    @Test
    public void test_constructor_initializes_fields_with_valid_params() {
        String joinCollection = "users";
        String localField = "userId";
        String remoteField = "id";
        String asField = "userDetails";

        JoinAggregationStep joinStep = new JoinAggregationStep(joinCollection, localField, remoteField, asField);

        assertEquals(AggregationStepType.JOIN, joinStep.getType());
        assertEquals(joinCollection, joinStep.getJoinCollection());
        assertEquals(localField, joinStep.getLocalField());
        assertEquals(remoteField, joinStep.getRemoteField());
        assertEquals(asField, joinStep.getAsField());
    }

    @Test
    public void test_constructor_handles_empty_string_params() {
        String emptyString = "";

        JoinAggregationStep joinStep = new JoinAggregationStep(emptyString, emptyString, emptyString, emptyString);

        assertEquals(AggregationStepType.JOIN, joinStep.getType());
        assertEquals(emptyString, joinStep.getJoinCollection());
        assertEquals(emptyString, joinStep.getLocalField());
        assertEquals(emptyString, joinStep.getRemoteField());
        assertEquals(emptyString, joinStep.getAsField());
    }

    @Test
    public void test_getters_and_setters() {
        JoinAggregationStep joinStep = new JoinAggregationStep("users", "userId", "id", "userDetails");

        assertEquals("users", joinStep.getJoinCollection());
        assertEquals("userId", joinStep.getLocalField());
        assertEquals("id", joinStep.getRemoteField());
        assertEquals("userDetails", joinStep.getAsField());

        joinStep.setJoinCollection("orders");
        joinStep.setLocalField("orderId");
        joinStep.setRemoteField("orderRef");
        joinStep.setAsField("orderDetails");

        assertEquals("orders", joinStep.getJoinCollection());
        assertEquals("orderId", joinStep.getLocalField());
        assertEquals("orderRef", joinStep.getRemoteField());
        assertEquals("orderDetails", joinStep.getAsField());
    }
}
