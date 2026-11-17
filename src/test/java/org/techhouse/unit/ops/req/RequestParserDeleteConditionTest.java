package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.operators.ScriptOperator;
import org.techhouse.test.TestUtils;

public class RequestParserDeleteConditionTest {
    private static final String PREFIX = "{\"type\":\"DELETE\",\"databaseName\":\"shop\",\"collectionName\":\"orders\"";

    @BeforeAll
    public static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    private static DeleteRequest parse(String rest) {
        return assertInstanceOf(DeleteRequest.class, RequestParser.parseRequest(PREFIX + rest + "}"));
    }

    @Test
    public void test_a_field_condition_is_parsed_into_a_field_operator() {
        final var request = parse(
                ",\"condition\":{\"fieldOperatorType\":\"EQUALS\",\"field\":\"status\"," + "\"value\":\"archived\"}");

        final var operator = assertInstanceOf(FieldOperator.class, request.getCondition());
        assertEquals(FieldOperatorType.EQUALS, operator.getFieldOperatorType());
        assertEquals("status", operator.getField());
        assertEquals("archived", operator.getValue().asJsonString().getValue());
        assertNull(request.get_id());
    }

    @Test
    public void test_a_conjunction_condition_keeps_its_nested_legs() {
        final var request = parse(",\"condition\":{\"conjunctionType\":\"AND\",\"operators\":["
                + "{\"fieldOperatorType\":\"GREATER_THAN\",\"field\":\"total\",\"value\":10},"
                + "{\"script\":\"export default (d) => d.total < 100;\"}]}");

        final var conjunction = assertInstanceOf(ConjunctionOperator.class, request.getCondition());
        assertEquals(ConjunctionOperatorType.AND, conjunction.getConjunctionType());
        assertInstanceOf(FieldOperator.class, conjunction.getOperators().get(0));
        assertInstanceOf(ScriptOperator.class, conjunction.getOperators().get(1));
    }

    @Test
    public void test_a_custom_operator_condition_is_parsed() {
        final var request = parse(",\"condition\":{\"customOperatorName\":\"distance\",\"field\":\"location\","
                + "\"value\":\"#geo(40.71,-74.0)\",\"comparator\":\"SMALLER_THAN\",\"distance\":1000}");

        assertInstanceOf(CustomOperator.class, request.getCondition());
    }

    @Test
    public void test_a_null_condition_reads_as_absent() {
        final var request = parse(",\"_id\":\"o-1\",\"condition\":null");

        assertNull(request.getCondition());
        assertEquals("o-1", request.get_id());
    }

    @Test
    public void test_an_id_delete_parses_as_before() {
        final var request = parse(",\"_id\":\"o-1\"");

        assertEquals("o-1", request.get_id());
        assertNull(request.getCondition());
    }
}
