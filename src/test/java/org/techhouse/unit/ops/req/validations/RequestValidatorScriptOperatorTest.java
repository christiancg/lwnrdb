package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ScriptMidOperator;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.ScriptOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.test.TestUtils;

public class RequestValidatorScriptOperatorTest {
    private static final String SOURCE = "export default (doc) => true;";

    // Another suite may have loaded a configuration with the switch off, so the ambient value is set
    // rather than assumed.
    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptsEnabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptsEnabled", true);
    }

    private static AggregateRequest aggregate(BaseAggregationStep... steps) {
        final var request = new AggregateRequest("testDB", "testCollection");
        request.setAggregationSteps(List.of(steps));
        return request;
    }

    private static FilterAggregationStep scriptFilter(String source) {
        return new FilterAggregationStep(new ScriptOperator(source));
    }

    @Test
    public void test_accepts_a_script_filter_when_enabled() {
        assertTrue(RequestValidator.validate(aggregate(scriptFilter(SOURCE))).isValid());
    }

    @Test
    public void test_accepts_a_script_map_operator_and_a_reduce_step() {
        final var map = new MapAggregationStep(
                List.of(new AddFieldMapOperator("total", null, new ScriptMidOperator(SOURCE))));
        assertTrue(RequestValidator.validate(aggregate(map)).isValid());
        final var reduce = new ReduceAggregationStep(SOURCE, new JsonNumber(0), "total");
        assertTrue(RequestValidator.validate(aggregate(reduce)).isValid());
    }

    @Test
    public void test_rejects_when_scripts_are_disabled() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptsEnabled", false);
        final var result = RequestValidator.validate(aggregate(scriptFilter(SOURCE)));
        assertFalse(result.isValid());
        assertEquals(ErrorCode.SCRIPTS_DISABLED, result.getErrorCode());
    }

    @Test
    public void test_rejects_a_reduce_step_when_scripts_are_disabled() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptsEnabled", false);
        final var result = RequestValidator
                .validate(aggregate(new ReduceAggregationStep(SOURCE, new JsonNumber(0), "total")));
        assertEquals(ErrorCode.SCRIPTS_DISABLED, result.getErrorCode());
    }

    @Test
    public void test_rejects_blank_source() {
        final var result = RequestValidator.validate(aggregate(scriptFilter("   ")));
        assertFalse(result.isValid());
        assertEquals(ErrorCode.VALIDATION_ERROR, result.getErrorCode());
    }

    @Test
    public void test_rejects_oversize_source() throws Exception {
        final var configuration = Configuration.getInstance();
        final var previous = configuration.getAggregationScriptMaxSourceBytes();
        try {
            TestUtils.setPrivateField(configuration, "aggregationScriptMaxSourceBytes", 8L);
            final var result = RequestValidator.validate(aggregate(scriptFilter(SOURCE)));
            assertFalse(result.isValid());
            assertEquals(ErrorCode.SCRIPT_TOO_LARGE, result.getErrorCode());
        } finally {
            TestUtils.setPrivateField(configuration, "aggregationScriptMaxSourceBytes", previous);
        }
    }

    @Test
    public void test_rejects_script_inside_listen() {
        final var request = new ListenRequest("testDB", "testCollection");
        request.setAggregationSteps(List.of(scriptFilter(SOURCE)));
        final var result = RequestValidator.validate(request);
        assertFalse(result.isValid());
        assertEquals(ErrorCode.SCRIPT_NOT_ALLOWED_IN_LISTEN, result.getErrorCode());
    }

    @Test
    public void test_listen_without_a_script_is_still_accepted() {
        final var request = new ListenRequest("testDB", "testCollection");
        request.setAggregationSteps(List.of());
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_walks_nested_conjunctions_for_scripts() {
        final var conjunction = new ConjunctionOperator(ConjunctionOperatorType.AND,
                List.of(new ConjunctionOperator(ConjunctionOperatorType.OR, List.of(new ScriptOperator("  ")))));
        final var result = RequestValidator.validate(aggregate(new FilterAggregationStep(conjunction)));
        assertFalse(result.isValid());
    }

    @Test
    public void test_reduce_requires_a_non_blank_result_field() {
        final var step = new ReduceAggregationStep(SOURCE, new JsonNumber(0), "total");
        step.setResultField("  ");
        assertFalse(RequestValidator.validate(aggregate(step)).isValid());
    }
}
