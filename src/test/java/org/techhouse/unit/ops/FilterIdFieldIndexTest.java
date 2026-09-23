package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;

public class FilterIdFieldIndexTest extends FilterResolutionSupport {

    private Cache seedIds(String... ids) throws IOException {
        final var cache = mixedTypeFixture();
        for (final var id : ids) {
            addTyped(cache, id, "payload", new JsonString("v-" + id));
        }
        return cache;
    }

    private Set<String> scanned(Cache cache, FieldOperator op) throws IOException {
        final var source = cache.getWholeCollection(TestGlobals.DB, TestGlobals.COLL).values().stream()
                .map(DbEntry::getData);
        return FilterOperatorHelper.processOperator(op, source, TestGlobals.DB, TestGlobals.COLL)
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).collect(Collectors.toSet());
    }

    private void assertIndexAgreesWithScan(Cache cache, FieldOperator op, Set<String> expected) throws IOException {
        assertEquals(expected, matched(op));
        assertEquals(scanned(cache, op), matched(op));
    }

    @Test
    public void test_filter_on_id_equals_answers_from_the_pk_index() throws IOException {
        final var cache = seedIds("a", "b", "c");
        assertIndexAgreesWithScan(cache,
                new FieldOperator(FieldOperatorType.EQUALS, Globals.PK_FIELD, new JsonString("a")), Set.of("a"));
    }

    @Test
    public void test_filter_on_id_equals_a_missing_id_answers_empty() throws IOException {
        final var cache = seedIds("a", "b", "c");
        assertIndexAgreesWithScan(cache,
                new FieldOperator(FieldOperatorType.EQUALS, Globals.PK_FIELD, new JsonString("zz")), Set.of());
    }

    @Test
    public void test_filter_on_id_not_equals_answers_the_complement() throws IOException {
        final var cache = seedIds("a", "b", "c");
        assertIndexAgreesWithScan(cache,
                new FieldOperator(FieldOperatorType.NOT_EQUALS, Globals.PK_FIELD, new JsonString("a")),
                Set.of("b", "c"));
    }

    @Test
    public void test_filter_on_id_contains_answers_by_substring() throws IOException {
        final var cache = seedIds("alpha", "alphabet", "beta");
        assertIndexAgreesWithScan(cache,
                new FieldOperator(FieldOperatorType.CONTAINS, Globals.PK_FIELD, new JsonString("alpha")),
                Set.of("alpha", "alphabet"));
    }

    @Test
    public void test_filter_on_id_in_answers_every_present_operand() throws IOException {
        final var cache = seedIds("a", "b", "c");
        final var operand = arrayOf(new JsonString("a"), new JsonString("c"), new JsonString("zz"));
        assertIndexAgreesWithScan(cache, new FieldOperator(FieldOperatorType.IN, Globals.PK_FIELD, operand),
                Set.of("a", "c"));
    }

    @Test
    public void test_filter_on_id_not_in_answers_the_complement() throws IOException {
        final var cache = seedIds("a", "b", "c");
        final var operand = arrayOf(new JsonString("a"), new JsonString("c"));
        assertIndexAgreesWithScan(cache, new FieldOperator(FieldOperatorType.NOT_IN, Globals.PK_FIELD, operand),
                Set.of("b"));
    }

    @Test
    public void test_filter_on_id_is_case_sensitive_like_find_by_id() throws IOException {
        final var cache = seedIds("Case1", "case1");
        assertIndexAgreesWithScan(cache,
                new FieldOperator(FieldOperatorType.EQUALS, Globals.PK_FIELD, new JsonString("case1")),
                Set.of("case1"));
        assertIndexAgreesWithScan(cache,
                new FieldOperator(FieldOperatorType.EQUALS, Globals.PK_FIELD, new JsonString("Case1")),
                Set.of("Case1"));
    }

    @Test
    public void test_an_ordinary_string_field_stays_case_insensitive() throws IOException {
        final var cache = mixedTypeFixture();
        addTyped(cache, "s1", "name", new JsonString("ABC"));
        assertIndexAgreesWithScan(cache, new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("abc")),
                Set.of("s1"));
    }

    @Test
    public void test_filter_on_id_with_a_number_operand_matches_nothing() throws IOException {
        final var cache = seedIds("1", "2");
        assertIndexAgreesWithScan(cache,
                new FieldOperator(FieldOperatorType.EQUALS, Globals.PK_FIELD, new JsonNumber(1)), Set.of());
    }

    @Test
    public void test_filter_on_id_with_an_empty_in_list_matches_nothing() throws IOException {
        final var cache = seedIds("a", "b");
        assertIndexAgreesWithScan(cache, new FieldOperator(FieldOperatorType.IN, Globals.PK_FIELD, arrayOf()),
                Set.of());
    }

    @Test
    public void test_filter_on_id_with_a_mixed_in_list_ignores_the_non_string_operands() throws IOException {
        final var cache = seedIds("a", "b");
        final var operand = arrayOf(new JsonNumber(1), new JsonString("a"));
        assertIndexAgreesWithScan(cache, new FieldOperator(FieldOperatorType.IN, Globals.PK_FIELD, operand),
                Set.of("a"));
    }

    @Test
    public void test_range_operators_on_id_match_nothing_on_both_paths() throws IOException {
        final var cache = seedIds("a", "b", "c");
        for (final var type : new FieldOperatorType[]{FieldOperatorType.GREATER_THAN,
                FieldOperatorType.GREATER_THAN_EQUALS, FieldOperatorType.SMALLER_THAN,
                FieldOperatorType.SMALLER_THAN_EQUALS}) {
            assertIndexAgreesWithScan(cache, new FieldOperator(type, Globals.PK_FIELD, new JsonString("b")), Set.of());
        }
    }

    @Test
    public void test_filter_on_an_unregistered_field_falls_back_to_the_scan() throws IOException {
        final var cache = mixedTypeFixture();
        addTyped(cache, "u1", "unindexed", new JsonString("x"));
        addTyped(cache, "u2", "unindexed", new JsonString("y"));
        assertIndexAgreesWithScan(cache, new FieldOperator(FieldOperatorType.EQUALS, "unindexed", new JsonString("x")),
                Set.of("u1"));
    }

    @Test
    public void test_filter_on_id_reads_only_the_matching_document() throws IOException {
        seedIds("a", "b", "c", "d", "e");
        final var analyze = new AnalyzeContext();
        AnalyzeContext.set(analyze);
        try {
            assertEquals(Set.of("a"),
                    matched(new FieldOperator(FieldOperatorType.EQUALS, Globals.PK_FIELD, new JsonString("a"))));
            assertTrue(analyze.getIndexesUsed().contains(Globals.PK_FIELD),
                    "the pk index must be reported as used: " + analyze.getIndexesUsed());
            assertEquals(1, analyze.getDocumentsScanned(),
                    "an _id equality must read exactly one document, not the whole collection");
        } finally {
            AnalyzeContext.clear();
        }
    }
}
