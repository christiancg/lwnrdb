package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.filter.FieldPredicateFactory;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FilterMembershipNullOperandTest {
    private static final String FIELD = "x";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        seed("a", JsonNull.INSTANCE);
        seed("b", new JsonNumber(1));
        seed("c", null);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void seed(String id, JsonBaseElement value) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(documentWith(id, value));
        processor.processMessage(request);
    }

    private static JsonObject documentWith(String id, JsonBaseElement value) {
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString(id));
        if (value != null) {
            document.add(FIELD, value);
        }
        return document;
    }

    private static JsonArray arrayOf(JsonBaseElement... elements) {
        final var array = new JsonArray();
        for (final var element : elements) {
            array.add(element);
        }
        return array;
    }

    private static boolean matches(FieldOperatorType operation, JsonArray operand, JsonBaseElement stored) {
        final var operator = new FieldOperator(operation, FIELD, operand);
        return FieldPredicateFactory.getTester(operator, operation).test(documentWith("doc", stored), FIELD);
    }

    private Set<String> idsOf(List<BaseAggregationStep> steps) {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(steps);
        final var answer = processor.processMessage(request);
        if (!(answer instanceof AggregateResponse response)) {
            assertEquals(ErrorCode.NO_RESULTS.getCode(), answer.getErrorCode());
            return Set.of();
        }
        return response.getResults().stream().map(row -> row.get(Globals.PK_FIELD).asJsonString().getValue())
                .collect(Collectors.toSet());
    }

    private Set<String> filtered(FieldOperatorType operation, JsonBaseElement operand) {
        return idsOf(List.of(new FilterAggregationStep(new FieldOperator(operation, FIELD, operand))));
    }

    private Set<String> scanned(FieldOperatorType operation, JsonBaseElement operand) {
        return idsOf(List.of(new SkipAggregationStep(0),
                new FilterAggregationStep(new FieldOperator(operation, FIELD, operand))));
    }

    @Test
    public void in_list_holding_null_matches_a_null_value() {
        assertTrue(matches(FieldOperatorType.IN, arrayOf(JsonNull.INSTANCE), JsonNull.INSTANCE));
        assertEquals(Set.of("a"), filtered(FieldOperatorType.IN, arrayOf(JsonNull.INSTANCE)));
    }

    @Test
    public void in_list_holding_null_and_a_number_matches_both() {
        assertEquals(Set.of("a", "b"), filtered(FieldOperatorType.IN, arrayOf(new JsonNumber(1), JsonNull.INSTANCE)));
    }

    @Test
    public void not_in_list_holding_null_excludes_a_null_value() {
        assertFalse(matches(FieldOperatorType.NOT_IN, arrayOf(JsonNull.INSTANCE), JsonNull.INSTANCE));
        assertEquals(Set.of("b"), filtered(FieldOperatorType.NOT_IN, arrayOf(JsonNull.INSTANCE)));
        assertEquals(Set.of(), filtered(FieldOperatorType.NOT_IN, arrayOf(new JsonNumber(1), JsonNull.INSTANCE)));
    }

    @Test
    public void not_in_list_without_null_still_keeps_a_null_value() {
        assertTrue(matches(FieldOperatorType.NOT_IN, arrayOf(new JsonNumber(1)), JsonNull.INSTANCE));
        assertFalse(matches(FieldOperatorType.IN, arrayOf(new JsonNumber(1)), JsonNull.INSTANCE));
        assertEquals(Set.of("a"), filtered(FieldOperatorType.NOT_IN, arrayOf(new JsonNumber(1))));
    }

    @Test
    public void missing_field_is_excluded_from_in_and_not_in() {
        assertFalse(matches(FieldOperatorType.IN, arrayOf(JsonNull.INSTANCE), null));
        assertFalse(matches(FieldOperatorType.NOT_IN, arrayOf(JsonNull.INSTANCE), null));
    }

    @Test
    public void in_and_equals_agree_on_null() {
        assertEquals(filtered(FieldOperatorType.EQUALS, JsonNull.INSTANCE),
                filtered(FieldOperatorType.IN, arrayOf(JsonNull.INSTANCE)));
        assertEquals(filtered(FieldOperatorType.NOT_EQUALS, JsonNull.INSTANCE),
                filtered(FieldOperatorType.NOT_IN, arrayOf(JsonNull.INSTANCE)));
    }

    @Test
    public void index_declines_a_list_starting_with_null() {
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, FIELD));
        for (final var operation : List.of(FieldOperatorType.IN, FieldOperatorType.NOT_IN)) {
            for (final var operand : List.of(arrayOf(JsonNull.INSTANCE), arrayOf(JsonNull.INSTANCE, new JsonNumber(1)),
                    arrayOf(new JsonNumber(1), JsonNull.INSTANCE))) {
                assertEquals(scanned(operation, operand), filtered(operation, operand),
                        operation + " " + operand.asList());
            }
        }
    }
}
