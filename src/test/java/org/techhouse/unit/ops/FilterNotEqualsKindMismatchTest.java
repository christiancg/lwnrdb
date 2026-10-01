package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
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

    @Test
    public void numberFieldNotEqualsStringOperandMatches() {
        assertTrue(matches(FieldOperatorType.NOT_EQUALS, new JsonString("b"), new JsonNumber(5)));
    }

    @Test
    public void stringFieldNotEqualsNumberOperandMatches() {
        assertTrue(matches(FieldOperatorType.NOT_EQUALS, new JsonNumber(5), new JsonString("a")));
    }

    @Test
    public void booleanFieldNotEqualsNumberOperandMatches() {
        assertTrue(matches(FieldOperatorType.NOT_EQUALS, new JsonNumber(1), new JsonBoolean(true)));
    }

    @Test
    public void customFieldNotEqualsPlainStringMatches() {
        assertTrue(matches(FieldOperatorType.NOT_EQUALS, new JsonString("abc"), new JsonGeo("#geo(1,2)")));
    }

    @Test
    public void customFieldNotEqualsOtherCustomClassMatches() {
        assertTrue(matches(FieldOperatorType.NOT_EQUALS, new JsonTime("#time(10:00:00)"), new JsonGeo("#geo(1,2)")));
    }

    @Test
    public void nullFieldNotEqualsScalarMatches() {
        assertTrue(matches(FieldOperatorType.NOT_EQUALS, new JsonNumber(5), JsonNull.INSTANCE));
    }

    @Test
    public void arrayFieldNotEqualsScalarMatches() {
        assertTrue(matches(FieldOperatorType.NOT_EQUALS, new JsonNumber(5), arrayOf(new JsonNumber(5))));
    }

    @Test
    public void objectFieldNotEqualsScalarMatches() {
        assertTrue(matches(FieldOperatorType.NOT_EQUALS, new JsonNumber(5), objectHolding(new JsonNumber(5))));
    }

    @Test
    public void missingFieldNotEqualsStillExcluded() {
        assertFalse(matches(FieldOperatorType.NOT_EQUALS, new JsonNumber(5), null));
    }

    @Test
    public void kindMismatchEqualsStillFalse() {
        assertFalse(matches(FieldOperatorType.EQUALS, new JsonString("5"), new JsonNumber(5)));
        assertFalse(matches(FieldOperatorType.EQUALS, new JsonNumber(5), JsonNull.INSTANCE));
        assertFalse(matches(FieldOperatorType.EQUALS, new JsonNumber(5), arrayOf(new JsonNumber(5))));
    }

    @Test
    public void kindMismatchRangeOperatorsStillFalse() {
        for (final var operation : List.of(FieldOperatorType.GREATER_THAN, FieldOperatorType.GREATER_THAN_EQUALS,
                FieldOperatorType.SMALLER_THAN, FieldOperatorType.SMALLER_THAN_EQUALS)) {
            assertFalse(matches(operation, new JsonString("b"), new JsonNumber(5)), operation.name());
            assertFalse(matches(operation, new JsonNumber(5), JsonNull.INSTANCE), operation.name());
            assertFalse(matches(operation, new JsonNumber(5), objectHolding(new JsonNumber(5))), operation.name());
        }
    }

    @Test
    public void containsOnANonArrayContainerStillFalse() {
        assertFalse(matches(FieldOperatorType.CONTAINS, new JsonNumber(5), objectHolding(new JsonNumber(5))));
        assertFalse(matches(FieldOperatorType.CONTAINS, new JsonNumber(5), JsonNull.INSTANCE));
    }

    @Test
    public void arrayFieldContainsScalarStillSearchesElements() {
        assertTrue(matches(FieldOperatorType.CONTAINS, new JsonNumber(5), arrayOf(new JsonNumber(5))));
        assertFalse(matches(FieldOperatorType.CONTAINS, new JsonNumber(6), arrayOf(new JsonNumber(5))));
    }

    @Test
    public void sameKindNotEqualsIsUnchanged() {
        assertTrue(matches(FieldOperatorType.NOT_EQUALS, new JsonNumber(6), new JsonNumber(5)));
        assertFalse(matches(FieldOperatorType.NOT_EQUALS, new JsonString("A"), new JsonString("a")));
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
                new JsonBoolean(false), JsonNull.INSTANCE, arrayOf(new JsonNumber(1)), objectHolding(new JsonNumber(1)),
                new JsonGeo("#geo(1,2)"));
        for (var i = 0; i < storedValues.size(); i++) {
            final var stored = storedValues.get(i);
            assertEquals(matches(FieldOperatorType.NOT_IN, arrayOf(operand), stored),
                    matches(FieldOperatorType.NOT_EQUALS, operand, stored), "stored value at " + i);
        }
    }
}
