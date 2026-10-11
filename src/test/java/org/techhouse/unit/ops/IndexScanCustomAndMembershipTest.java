package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;

public class IndexScanCustomAndMembershipTest extends FilterResolutionSupport {
    private static final String WHEN = "when";

    @Test
    public void test_distinct_on_a_custom_field_buckets_the_same_with_and_without_an_index() throws Exception {
        insert("a", WHEN, new JsonString("#datetime(2024-01-01T10:00)"));
        insert("b", WHEN, new JsonString("#datetime(2024-01-01T10:00:00)"));
        final var viaScan = rows(new DistinctAggregationStep(WHEN)).size();

        indexField(WHEN);
        final var viaIndex = rows(new DistinctAggregationStep(WHEN)).size();

        assertEquals(viaScan, viaIndex,
                "the build path bucketed custom values by their raw spelling while incremental maintenance"
                        + " matched them semantically, so the two disagreed with each other and with the scan");
    }

    private static FilterAggregationStep membership(FieldOperatorType type, JsonBaseElement... operands) {
        return filter(type, WHEN, arrayOf(operands));
    }

    @Test
    public void test_in_matches_a_custom_operand_spelled_differently_with_and_without_an_index() throws Exception {
        insert("a", WHEN, new JsonDateTime("#datetime(2024-01-01T10:00)"));
        insert("b", WHEN, new JsonDateTime("#datetime(2024-02-02T11:00:00)"));
        final var operand = membership(FieldOperatorType.IN, new JsonDateTime("#datetime(2024-01-01T10:00:00)"));

        final var viaScan = ids(operand);
        indexField(WHEN);
        final var viaIndex = ids(operand);

        assertEquals(List.of("a"), viaScan, "IN must match a custom value the way EQUALS does, semantically");
        assertEquals(viaScan, viaIndex);
    }

    @Test
    public void test_not_in_excludes_a_custom_operand_spelled_differently_with_and_without_an_index() throws Exception {
        insert("a", WHEN, new JsonDateTime("#datetime(2024-01-01T10:00)"));
        insert("b", WHEN, new JsonDateTime("#datetime(2024-02-02T11:00:00)"));
        final var operand = membership(FieldOperatorType.NOT_IN, new JsonDateTime("#datetime(2024-01-01T10:00:00)"));

        final var viaScan = ids(operand);
        indexField(WHEN);
        final var viaIndex = ids(operand);

        assertEquals(List.of("b"), viaScan);
        assertEquals(viaScan, viaIndex);
    }

    @Test
    public void test_in_matches_a_case_mismatched_string_with_and_without_an_index() throws Exception {
        insert("a", WHEN, new JsonString("Alpha"));
        insert("b", WHEN, new JsonString("beta"));
        final var operand = membership(FieldOperatorType.IN, new JsonString("ALPHA"));

        final var viaScan = ids(operand);
        indexField(WHEN);
        final var viaIndex = ids(operand);

        assertEquals(List.of("a"), viaScan, "IN must match a string the way EQUALS does, case-insensitively");
        assertEquals(viaScan, viaIndex);
    }

    @Test
    public void test_not_in_excludes_a_case_mismatched_string_with_and_without_an_index() throws Exception {
        insert("a", WHEN, new JsonString("Alpha"));
        insert("b", WHEN, new JsonString("beta"));
        final var operand = membership(FieldOperatorType.NOT_IN, new JsonString("ALPHA"));

        final var viaScan = ids(operand);
        indexField(WHEN);
        final var viaIndex = ids(operand);

        assertEquals(List.of("b"), viaScan);
        assertEquals(viaScan, viaIndex);
    }

    @Test
    public void test_in_matches_a_null_valued_document_when_the_list_holds_null() throws Exception {
        insert("a", WHEN, new JsonString("Alpha"));
        insert("n", WHEN, JsonNull.INSTANCE);
        final var listed = membership(FieldOperatorType.IN, JsonNull.INSTANCE, new JsonString("ALPHA"));
        final var unlisted = membership(FieldOperatorType.IN, new JsonString("ALPHA"));

        assertEquals(List.of("a", "n"), ids(listed), "a null-valued document is a member of a list holding null");
        assertEquals(List.of("a"), ids(unlisted), "and of no other list");
    }

    @Test
    public void test_in_with_an_object_operand_stays_exact() throws Exception {
        final var stored = new JsonObject();
        stored.add("k", new JsonString("V"));
        insert("o", WHEN, stored);
        final var mismatched = new JsonObject();
        mismatched.add("k", new JsonString("v"));

        assertEquals(List.of(), ids(membership(FieldOperatorType.IN, mismatched)),
                "an object operand is resolved by hash on the index side, so the scan must stay exact too");
    }
}
