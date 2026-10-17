package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.PipelineContext;
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

    private static Arguments idCase(String name, String[] ids, FieldOperatorType type, JsonBaseElement operand,
            Set<String> expected) {
        return Arguments.of(name, ids, type, operand, expected);
    }

    private static Stream<Arguments> idFieldCases() {
        final var abc = new String[]{"a", "b", "c"};
        final var cases = Stream.<Arguments>builder();
        cases.add(idCase("test_filter_on_id_equals_answers_from_the_pk_index", abc, FieldOperatorType.EQUALS,
                new JsonString("a"), Set.of("a")));
        cases.add(idCase("test_filter_on_id_equals_a_missing_id_answers_empty", abc, FieldOperatorType.EQUALS,
                new JsonString("zz"), Set.of()));
        cases.add(idCase("test_filter_on_id_not_equals_answers_the_complement", abc, FieldOperatorType.NOT_EQUALS,
                new JsonString("a"), Set.of("b", "c")));
        cases.add(idCase("test_filter_on_id_contains_answers_by_substring", new String[]{"alpha", "alphabet", "beta"},
                FieldOperatorType.CONTAINS, new JsonString("alpha"), Set.of("alpha", "alphabet")));
        cases.add(idCase("test_filter_on_id_in_answers_every_present_operand", abc, FieldOperatorType.IN,
                arrayOf(new JsonString("a"), new JsonString("c"), new JsonString("zz")), Set.of("a", "c")));
        cases.add(idCase("test_filter_on_id_not_in_answers_the_complement", abc, FieldOperatorType.NOT_IN,
                arrayOf(new JsonString("a"), new JsonString("c")), Set.of("b")));
        final var cased = new String[]{"Case1", "case1"};
        cases.add(idCase("test_filter_on_id_is_case_sensitive_like_find_by_id lower", cased, FieldOperatorType.EQUALS,
                new JsonString("case1"), Set.of("case1")));
        cases.add(idCase("test_filter_on_id_is_case_sensitive_like_find_by_id upper", cased, FieldOperatorType.EQUALS,
                new JsonString("Case1"), Set.of("Case1")));
        cases.add(idCase("test_filter_on_id_in_stays_case_sensitive_on_both_paths", cased, FieldOperatorType.IN,
                arrayOf(new JsonString("Case1")), Set.of("Case1")));
        cases.add(idCase("test_filter_on_id_not_in_stays_case_sensitive_on_both_paths", cased, FieldOperatorType.NOT_IN,
                arrayOf(new JsonString("Case1")), Set.of("case1")));
        final var numeric = new String[]{"1", "2"};
        cases.add(idCase("test_filter_on_id_with_a_number_operand_matches_nothing", numeric, FieldOperatorType.EQUALS,
                new JsonNumber(1), Set.of()));
        cases.add(idCase("test_filter_on_id_not_equals_a_number_operand_matches_nothing", numeric,
                FieldOperatorType.NOT_EQUALS, new JsonNumber(1), Set.of()));
        cases.add(idCase("test_filter_on_id_not_equals_an_array_operand_matches_nothing", abc,
                FieldOperatorType.NOT_EQUALS, arrayOf(), Set.of()));
        cases.add(idCase("test_filter_on_id_not_equals_an_object_operand_matches_nothing", abc,
                FieldOperatorType.NOT_EQUALS, new JsonObject(), Set.of()));
        final var ab = new String[]{"a", "b"};
        cases.add(idCase("test_filter_on_id_with_an_empty_in_list_matches_nothing", ab, FieldOperatorType.IN, arrayOf(),
                Set.of()));
        cases.add(idCase("test_filter_on_id_with_a_mixed_in_list_ignores_the_non_string_operands", ab,
                FieldOperatorType.IN, arrayOf(new JsonNumber(1), new JsonString("a")), Set.of("a")));
        for (final var type : new FieldOperatorType[]{FieldOperatorType.GREATER_THAN,
                FieldOperatorType.GREATER_THAN_EQUALS, FieldOperatorType.SMALLER_THAN,
                FieldOperatorType.SMALLER_THAN_EQUALS}) {
            cases.add(idCase("test_range_operators_on_id_match_nothing_on_both_paths " + type, abc, type,
                    new JsonString("b"), Set.of()));
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("idFieldCases")
    public void test_filter_on_id_agrees_with_the_scan(String name, String[] ids, FieldOperatorType type,
            JsonBaseElement operand, Set<String> expected) throws IOException {
        final var cache = seedIds(ids);
        assertIndexAgreesWithScan(cache, new FieldOperator(type, Globals.PK_FIELD, operand), expected);
    }

    private static Stream<Arguments> otherFieldCases() {
        return Stream.of(
                Arguments.of("test_an_ordinary_string_field_stays_case_insensitive", "name", List.of("s1", "ABC"),
                        FieldOperatorType.EQUALS, new JsonString("abc"), Set.of("s1")),
                Arguments.of("test_filter_on_an_array_field_not_equals_a_non_array_value_still_matches", "tags",
                        List.of("s1", "x"), FieldOperatorType.NOT_EQUALS, arrayOf(), Set.of("s1")),
                Arguments.of("test_filter_on_an_object_field_not_equals_a_non_object_value_still_matches", "meta",
                        List.of("s1", "x"), FieldOperatorType.NOT_EQUALS, new JsonObject(), Set.of("s1")),
                Arguments.of("test_filter_on_an_unregistered_field_falls_back_to_the_scan", "unindexed",
                        List.of("u1", "x", "u2", "y"), FieldOperatorType.EQUALS, new JsonString("x"), Set.of("u1")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherFieldCases")
    public void test_filter_on_a_non_id_field_agrees_with_the_scan(String name, String field, List<String> idValuePairs,
            FieldOperatorType type, JsonBaseElement operand, Set<String> expected) throws IOException {
        final var cache = mixedTypeFixture();
        for (var i = 0; i < idValuePairs.size(); i += 2) {
            addTyped(cache, idValuePairs.get(i), field, new JsonString(idValuePairs.get(i + 1)));
        }
        assertIndexAgreesWithScan(cache, new FieldOperator(type, field, operand), expected);
    }

    @Test
    public void test_filter_on_id_reads_only_the_matching_document() throws IOException {
        seedIds("a", "b", "c", "d", "e");
        final var analyze = new AnalyzeContext();
        assertEquals(Set.of("a"),
                matched(new FieldOperator(FieldOperatorType.EQUALS, Globals.PK_FIELD, new JsonString("a")),
                        PipelineContext.of(0L, Long.MAX_VALUE, analyze)));
        assertTrue(analyze.getIndexesUsed().contains(Globals.PK_FIELD),
                "the pk index must be reported as used: " + analyze.getIndexesUsed());
        assertEquals(1, analyze.getDocumentsScanned(),
                "an _id equality must read exactly one document, not the whole collection");
    }
}
