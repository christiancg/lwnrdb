package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.techhouse.config.Globals;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.index.PrimaryKeyIndexResolver;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.unit.ops.FilterResolutionSupport;

public class PrimaryKeyIndexResolverTest extends FilterResolutionSupport {
    @BeforeEach
    public void seedIds() throws IOException {
        mixedTypeFixture();
        for (final var id : new String[]{"a", "b", "c"}) {
            insert(id);
        }
    }

    private Set<String> resolve(FieldOperatorType type, JsonBaseElement operand) throws IOException {
        return PrimaryKeyIndexResolver.resolve(new FieldOperator(type, Globals.PK_FIELD, operand), TestGlobals.DB,
                TestGlobals.COLL);
    }

    private static Stream<Arguments> resolvedOperands() {
        return Stream.of(
                Arguments.of("test_equals_resolves_the_single_id", FieldOperatorType.EQUALS, new JsonString("b"),
                        Set.of("b")),
                Arguments.of("test_equals_on_a_missing_id_resolves_empty", FieldOperatorType.EQUALS,
                        new JsonString("missing"), Set.of()),
                Arguments.of("test_not_equals_resolves_the_complement", FieldOperatorType.NOT_EQUALS,
                        new JsonString("b"), Set.of("a", "c")),
                Arguments.of("test_in_resolves_every_present_operand", FieldOperatorType.IN,
                        arrayOf(new JsonString("a"), new JsonString("c")), Set.of("a", "c")),
                Arguments.of("test_not_in_resolves_the_complement", FieldOperatorType.NOT_IN,
                        arrayOf(new JsonString("a"), new JsonString("c")), Set.of("b")),
                Arguments.of("test_contains_resolves_by_substring", FieldOperatorType.CONTAINS, new JsonString("a"),
                        Set.of("a")),
                Arguments.of("test_not_equals_still_complements_an_empty_string_operand", FieldOperatorType.NOT_EQUALS,
                        new JsonString(""), Set.of("a", "b", "c")),
                Arguments.of("test_equals_and_contains_still_answer_empty_for_a_non_string_operand EQUALS",
                        FieldOperatorType.EQUALS, new JsonNumber(5), Set.of()),
                Arguments.of("test_equals_and_contains_still_answer_empty_for_a_non_string_operand CONTAINS",
                        FieldOperatorType.CONTAINS, new JsonNumber(5), Set.of()),
                Arguments.of("test_not_in_still_complements_a_non_string_operand_list", FieldOperatorType.NOT_IN,
                        arrayOf(new JsonNumber(5), JsonNull.INSTANCE), Set.of("a", "b", "c")),
                Arguments.of("test_a_non_array_operand_for_in_resolves_empty", FieldOperatorType.IN,
                        new JsonString("a"), Set.of()),
                Arguments.of("test_resolution_is_case_sensitive", FieldOperatorType.EQUALS, new JsonString("A"),
                        Set.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resolvedOperands")
    public void test_the_primary_key_resolver_answers(String name, FieldOperatorType type, JsonBaseElement operand,
            Set<String> expected) throws IOException {
        assertEquals(expected, resolve(type, operand), name);
    }

    private static Stream<Arguments> declinedOperands() {
        final var rangeOperand = new JsonString("b");
        return Stream.of(
                Arguments.of("test_range_operators_decline_so_the_caller_scans GREATER_THAN",
                        FieldOperatorType.GREATER_THAN, rangeOperand),
                Arguments.of("test_range_operators_decline_so_the_caller_scans GREATER_THAN_EQUALS",
                        FieldOperatorType.GREATER_THAN_EQUALS, rangeOperand),
                Arguments.of("test_range_operators_decline_so_the_caller_scans SMALLER_THAN",
                        FieldOperatorType.SMALLER_THAN, rangeOperand),
                Arguments.of("test_range_operators_decline_so_the_caller_scans SMALLER_THAN_EQUALS",
                        FieldOperatorType.SMALLER_THAN_EQUALS, rangeOperand),
                Arguments.of("test_not_equals_declines_every_operand_that_is_not_a_primary_key JsonNumber",
                        FieldOperatorType.NOT_EQUALS, new JsonNumber(5)),
                Arguments.of("test_not_equals_declines_every_operand_that_is_not_a_primary_key JsonNull",
                        FieldOperatorType.NOT_EQUALS, JsonNull.INSTANCE),
                Arguments.of("test_not_equals_declines_every_operand_that_is_not_a_primary_key JsonObject",
                        FieldOperatorType.NOT_EQUALS, new JsonObject()),
                Arguments.of("test_not_equals_declines_every_operand_that_is_not_a_primary_key JsonArray",
                        FieldOperatorType.NOT_EQUALS, arrayOf()),
                Arguments.of("test_not_equals_declines_every_operand_that_is_not_a_primary_key JsonBoolean",
                        FieldOperatorType.NOT_EQUALS, new JsonBoolean(true)),
                Arguments.of("test_not_equals_declines_every_operand_that_is_not_a_primary_key JsonDateTime",
                        FieldOperatorType.NOT_EQUALS, new JsonDateTime("#datetime(2024-01-01T10:00:00)")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declinedOperands")
    public void test_the_primary_key_resolver_declines_so_the_caller_scans(String name, FieldOperatorType type,
            JsonBaseElement operand) throws IOException {
        assertNull(resolve(type, operand), name + " must decline rather than resolve");
    }
}
