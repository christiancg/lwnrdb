package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.techhouse.config.Globals;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.filter.FieldPredicateFactory;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;

public class FilterNotEqualsKindMismatchTest {
    private static final String FIELD = "x";

    private static JsonObject documentWith(String field, JsonBaseElement value) {
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString("doc"));
        if (value != null) {
            document.add(field, value);
        }
        return document;
    }

    private static boolean matches(FieldOperatorType operation, JsonBaseElement operand, JsonBaseElement stored) {
        return matchesOn(FIELD, operation, operand, stored);
    }

    private static boolean matchesOn(String field, FieldOperatorType operation, JsonBaseElement operand,
            JsonBaseElement stored) {
        final var operator = new FieldOperator(operation, field, operand);
        return FieldPredicateFactory.getTester(operator, operation).test(documentWith(field, stored), field);
    }

    private static JsonArray arrayOf(JsonBaseElement... elements) {
        final var array = new JsonArray();
        for (final var element : elements) {
            array.add(element);
        }
        return array;
    }

    private static JsonObject objectHolding(JsonBaseElement value) {
        final var object = new JsonObject();
        object.add("k", value);
        return object;
    }

    private static Arguments row(String name, FieldOperatorType operation, JsonBaseElement operand,
            JsonBaseElement stored, boolean expected) {
        return Arguments.of(name, operation, operand, stored, expected);
    }

    private static Stream<Arguments> truthTable() {
        final var five = new JsonNumber(5);
        final var rows = Stream.<Arguments>builder();
        rows.add(row("numberFieldNotEqualsStringOperandMatches", FieldOperatorType.NOT_EQUALS, new JsonString("b"),
                new JsonNumber(5), true));
        rows.add(row("stringFieldNotEqualsNumberOperandMatches", FieldOperatorType.NOT_EQUALS, five,
                new JsonString("a"), true));
        rows.add(row("booleanFieldNotEqualsNumberOperandMatches", FieldOperatorType.NOT_EQUALS, new JsonNumber(1),
                JsonBoolean.of(true), true));
        rows.add(row("customFieldNotEqualsPlainStringMatches", FieldOperatorType.NOT_EQUALS, new JsonString("abc"),
                new JsonGeo("#geo(1,2)"), true));
        rows.add(row("customFieldNotEqualsOtherCustomClassMatches", FieldOperatorType.NOT_EQUALS,
                new JsonTime("#time(10:00:00)"), new JsonGeo("#geo(1,2)"), true));
        rows.add(row("nullFieldNotEqualsScalarMatches", FieldOperatorType.NOT_EQUALS, five, JsonNull.INSTANCE, true));
        rows.add(row("arrayFieldNotEqualsScalarMatches", FieldOperatorType.NOT_EQUALS, five, arrayOf(new JsonNumber(5)),
                true));
        rows.add(row("objectFieldNotEqualsScalarMatches", FieldOperatorType.NOT_EQUALS, five,
                objectHolding(new JsonNumber(5)), true));
        rows.add(row("missingFieldNotEqualsStillExcluded", FieldOperatorType.NOT_EQUALS, five, null, false));
        rows.add(row("kindMismatchEqualsStillFalse string", FieldOperatorType.EQUALS, new JsonString("5"),
                new JsonNumber(5), false));
        rows.add(row("kindMismatchEqualsStillFalse null", FieldOperatorType.EQUALS, five, JsonNull.INSTANCE, false));
        rows.add(row("kindMismatchEqualsStillFalse array", FieldOperatorType.EQUALS, five, arrayOf(new JsonNumber(5)),
                false));
        for (final var operation : List.of(FieldOperatorType.GREATER_THAN, FieldOperatorType.GREATER_THAN_EQUALS,
                FieldOperatorType.SMALLER_THAN, FieldOperatorType.SMALLER_THAN_EQUALS)) {
            final var name = "kindMismatchRangeOperatorsStillFalse " + operation.name();
            rows.add(row(name + " string", operation, new JsonString("b"), new JsonNumber(5), false));
            rows.add(row(name + " null", operation, five, JsonNull.INSTANCE, false));
            rows.add(row(name + " object", operation, five, objectHolding(new JsonNumber(5)), false));
        }
        rows.add(row("containsOnANonArrayContainerStillFalse object", FieldOperatorType.CONTAINS, five,
                objectHolding(new JsonNumber(5)), false));
        rows.add(row("containsOnANonArrayContainerStillFalse null", FieldOperatorType.CONTAINS, five, JsonNull.INSTANCE,
                false));
        rows.add(row("arrayFieldContainsScalarStillSearchesElements present", FieldOperatorType.CONTAINS, five,
                arrayOf(new JsonNumber(5)), true));
        rows.add(row("arrayFieldContainsScalarStillSearchesElements absent", FieldOperatorType.CONTAINS,
                new JsonNumber(6), arrayOf(new JsonNumber(5)), false));
        rows.add(row("sameKindNotEqualsIsUnchanged number", FieldOperatorType.NOT_EQUALS, new JsonNumber(6),
                new JsonNumber(5), true));
        rows.add(row("sameKindNotEqualsIsUnchanged string", FieldOperatorType.NOT_EQUALS, new JsonString("A"),
                new JsonString("a"), false));
        return rows.build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("truthTable")
    public void kindMismatchTruthTable(String name, FieldOperatorType operation, JsonBaseElement operand,
            JsonBaseElement stored, boolean expected) {
        assertEquals(expected, matches(operation, operand, stored), name);
    }

    @Test
    public void pkFieldNotEqualsNumberOperandStillDeclines() {
        assertFalse(
                matchesOn(Globals.PK_FIELD, FieldOperatorType.NOT_EQUALS, new JsonNumber(5), new JsonString("doc")));
    }

    @Test
    public void notEqualsAgreesWithNotInForEveryKind() {
        final var operand = new JsonString("b");
        final List<JsonBaseElement> storedValues = List.of(new JsonNumber(5), new JsonString("a"), new JsonString("B"),
                JsonBoolean.of(false), JsonNull.INSTANCE, arrayOf(new JsonNumber(1)), objectHolding(new JsonNumber(1)),
                new JsonGeo("#geo(1,2)"));
        for (var i = 0; i < storedValues.size(); i++) {
            final var stored = storedValues.get(i);
            assertEquals(matches(FieldOperatorType.NOT_IN, arrayOf(operand), stored),
                    matches(FieldOperatorType.NOT_EQUALS, operand, stored), "stored value at " + i);
        }
    }
}
