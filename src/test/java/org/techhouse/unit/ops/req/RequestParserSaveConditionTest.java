package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestUtils;

public class RequestParserSaveConditionTest {
    private static final String PREFIX = "{\"type\":\"SAVE\",\"databaseName\":\"shop\",\"collectionName\":\"orders\"";

    @BeforeAll
    public static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    private static SaveRequest parse(String rest) {
        return assertInstanceOf(SaveRequest.class, RequestParser.parseRequest(PREFIX + rest + "}"));
    }

    @Test
    public void test_a_field_condition_is_parsed_beside_the_object() {
        final var request = parse(",\"condition\":{\"fieldOperatorType\":\"EQUALS\",\"field\":\"status\","
                + "\"value\":\"pending\"},\"object\":{\"status\":\"expired\"}");

        final var operator = assertInstanceOf(FieldOperator.class, request.getCondition());
        assertEquals(FieldOperatorType.EQUALS, operator.getFieldOperatorType());
        assertEquals("status", operator.getField());
        assertEquals("expired", request.getObject().get("status").asJsonString().getValue());
        assertNull(request.get_id());
    }

    @Test
    public void test_a_conjunction_condition_is_parsed_recursively() {
        final var request = parse(",\"condition\":{\"conjunctionType\":\"AND\",\"operators\":["
                + "{\"fieldOperatorType\":\"EQUALS\",\"field\":\"status\",\"value\":\"pending\"},"
                + "{\"fieldOperatorType\":\"SMALLER_THAN\",\"field\":\"total\",\"value\":100}]},\"object\":{\"a\":1}");

        final var conjunction = assertInstanceOf(ConjunctionOperator.class, request.getCondition());
        assertEquals(ConjunctionOperatorType.AND, conjunction.getConjunctionType());
        assertEquals(2, conjunction.getOperators().size());
    }

    @Test
    public void test_a_null_condition_is_a_plain_save() {
        final var request = parse(",\"condition\":null,\"object\":{\"_id\":\"o-1\",\"a\":1}");

        assertNull(request.getCondition());
        assertEquals("o-1", request.get_id());
    }

    @Test
    public void test_an_object_id_still_becomes_the_request_id_beside_a_condition() {
        final var request = parse(",\"condition\":{\"fieldOperatorType\":\"EQUALS\",\"field\":\"a\",\"value\":1},"
                + "\"object\":{\"_id\":\"o-1\"}");

        assertEquals("o-1", request.get_id());
    }
}
