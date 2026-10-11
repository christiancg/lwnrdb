package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.FieldOperatorType;

public class FilterArrayObjectOperandTest extends FilterResolutionSupport {
    private static final String ARRAY_FIELD = "tags";
    private static final String OBJECT_FIELD = "meta";

    private static JsonArray arrayOperand() {
        final var array = new JsonArray();
        array.add(new JsonString("a"));
        array.add(new JsonString("b"));
        return array;
    }

    private static JsonObject objectOperand() {
        final var object = new JsonObject();
        object.add("k", new JsonString("v"));
        return object;
    }

    private List<String> arrayFilter(FieldOperatorType type) throws IOException {
        return ids(filter(type, ARRAY_FIELD, arrayOperand()));
    }

    private static Stream<Arguments> containerOperandAgainstAScalarOrNullField() {
        return Stream.of(
                Arguments.of("test_not_equals_array_operand_against_scalar_field_matches", "s_string", ARRAY_FIELD,
                        new JsonString("solo"), FieldOperatorType.NOT_EQUALS, List.of("s_string")),
                Arguments.of("test_not_equals_array_operand_against_null_field_matches", "s_null", ARRAY_FIELD,
                        JsonNull.INSTANCE, FieldOperatorType.NOT_EQUALS, List.of("s_null")),
                Arguments.of("test_not_in_array_operand_against_null_field_matches", "s_null", ARRAY_FIELD,
                        JsonNull.INSTANCE, FieldOperatorType.NOT_IN, List.of("s_null")),
                Arguments.of("test_not_equals_object_operand_against_scalar_field_matches", "m_number", OBJECT_FIELD,
                        new JsonNumber(3), FieldOperatorType.NOT_EQUALS, List.of("m_number")),
                Arguments.of("test_equals_array_operand_against_scalar_field_still_does_not_match", "s_string",
                        ARRAY_FIELD, new JsonString("solo"), FieldOperatorType.EQUALS, List.of()),
                Arguments.of("test_equals_object_operand_against_scalar_field_still_does_not_match", "m_number",
                        OBJECT_FIELD, new JsonNumber(3), FieldOperatorType.EQUALS, List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containerOperandAgainstAScalarOrNullField")
    public void test_a_container_operand_against_a_scalar_or_null_field(String name, String id, String field,
            JsonBaseElement stored, FieldOperatorType type, List<String> expected) throws IOException {
        insert(id, field, stored);
        final JsonBaseElement operand = ARRAY_FIELD.equals(field) ? arrayOperand() : objectOperand();

        assertEquals(expected, ids(filter(type, field, operand)), name);
    }

    @Test
    public void test_equals_on_array_still_requires_matching_length_and_order() throws IOException {
        final var reversed = new JsonArray();
        reversed.add(new JsonString("b"));
        reversed.add(new JsonString("a"));
        final var longer = arrayOperand();
        longer.add(new JsonString("c"));
        insert("same", ARRAY_FIELD, arrayOperand());
        insert("reversed", ARRAY_FIELD, reversed);
        insert("longer", ARRAY_FIELD, longer);
        assertEquals(List.of("same"), arrayFilter(FieldOperatorType.EQUALS));
        assertEquals(List.of("longer", "reversed"), arrayFilter(FieldOperatorType.NOT_EQUALS));
    }

    @Test
    public void test_equals_on_object_is_still_key_order_independent() throws IOException {
        final var reordered = new JsonObject();
        reordered.add("x", new JsonNumber(1));
        reordered.add("k", new JsonString("v"));
        final var operand = new JsonObject();
        operand.add("k", new JsonString("v"));
        operand.add("x", new JsonNumber(1));
        final var subset = new JsonObject();
        subset.add("k", new JsonString("v"));
        insert("reordered", OBJECT_FIELD, reordered);
        insert("subset", OBJECT_FIELD, subset);
        assertEquals(List.of("reordered"), ids(filter(FieldOperatorType.EQUALS, OBJECT_FIELD, operand)));
    }
}
