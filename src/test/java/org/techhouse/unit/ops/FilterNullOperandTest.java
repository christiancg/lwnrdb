package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;

/**
 * A null operand is the one kind that is not a JsonPrimitive, so it reaches getTester's fallthrough
 * rather than any of the typed branches. Agreement testing cannot catch a wrong answer here - the
 * index has no JsonNull case and always declines, so both paths scan and agree with each other
 * however wrong they are. These cases pin the answers themselves.
 */
public class FilterNullOperandTest extends FilterResolutionSupport {
    private static final String FIELD = "status";

    @BeforeEach
    public void seedStatuses() throws IOException {
        insert("n_null", FIELD, JsonNull.INSTANCE);
        insert("n_string", FIELD, new JsonString("open"));
        insert("n_number", FIELD, new JsonNumber(7));
        insert("n_absent", FIELD, null);
    }

    private List<String> againstNull(FieldOperatorType type) throws IOException {
        return ids(filter(type, FIELD, JsonNull.INSTANCE));
    }

    @Test
    public void test_equals_null_returns_only_the_null_valued_document() throws IOException {
        assertEquals(List.of("n_null"), againstNull(FieldOperatorType.EQUALS));
    }

    @Test
    public void test_not_equals_null_returns_the_non_null_documents() throws IOException {
        assertEquals(List.of("n_number", "n_string"), againstNull(FieldOperatorType.NOT_EQUALS));
    }

    @Test
    public void test_range_operators_against_null_match_nothing() throws IOException {
        for (final var type : List.of(FieldOperatorType.GREATER_THAN, FieldOperatorType.GREATER_THAN_EQUALS,
                FieldOperatorType.SMALLER_THAN, FieldOperatorType.SMALLER_THAN_EQUALS)) {
            assertEquals(List.of(), againstNull(type), type + " has no meaning against null");
        }
    }

    @Test
    public void test_contains_null_matches_nothing() throws IOException {
        assertEquals(List.of(), againstNull(FieldOperatorType.CONTAINS));
    }

    @Test
    public void test_a_document_missing_the_field_matches_neither_equals_nor_not_equals_null() throws IOException {
        assertFalse(againstNull(FieldOperatorType.EQUALS).contains("n_absent"), "an absent field is not a null value");
        assertFalse(againstNull(FieldOperatorType.NOT_EQUALS).contains("n_absent"),
                "an absent field is not a value that differs from null");
    }

    @Test
    public void test_nor_with_a_null_operand_agrees_with_the_inverted_filter() throws IOException {
        final var nor = ids(new FilterAggregationStep(new ConjunctionOperator(ConjunctionOperatorType.NOR,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, FIELD, JsonNull.INSTANCE)))));

        assertEquals(List.of("n_absent", "n_number", "n_string"), nor);
    }
}
