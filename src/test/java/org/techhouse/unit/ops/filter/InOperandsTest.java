package org.techhouse.unit.ops.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonDateTime;
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
import org.techhouse.utils.CaseFolding;

public class InOperandsTest {
    private static final List<String> STRINGS = List.of("straße", "STRASSE", "Straße", "İstanbul", "istanbul",
            "ISTANBUL", "😀smile", "😀SMILE", "abc", "ABC", "Abc", "", "x", "X", "ß", "ẞ", "Aa", "BB", "id-1", "ID-1",
            "\uD800", "k");
    private static final List<Double> NUMBERS = List.of(0.0, -0.0, 1.0, -1.0, 2.5, 1e300, -7.0, 42.0, Double.NaN);
    private static final List<String> DATES = List.of("2024-01-01T00:00:00", "2024-01-01T00:00:01",
            "2023-12-31T23:59:59");

    private static JsonBaseElement scalar(Random random) {
        return switch (random.nextInt(7)) {
            case 0, 1 -> new JsonString(STRINGS.get(random.nextInt(STRINGS.size())));
            case 2 -> new JsonNumber(NUMBERS.get(random.nextInt(NUMBERS.size())));
            case 3 -> JsonBoolean.of(random.nextBoolean());
            case 4 -> new JsonDateTime("#datetime(" + DATES.get(random.nextInt(DATES.size())) + ")");
            case 5 -> random.nextBoolean() ? new JsonTime("#time(10:00:00)") : JsonNull.INSTANCE;
            default -> new JsonString(STRINGS.get(random.nextInt(4)).toUpperCase(Locale.ROOT));
        };
    }

    private static JsonBaseElement element(Random random, int depth) {
        final var kind = depth > 1 ? 0 : random.nextInt(5);
        if (kind == 3) {
            final var array = new JsonArray();
            for (var i = random.nextInt(3); i > 0; i--) {
                array.add(element(random, depth + 1));
            }
            return array;
        }
        if (kind == 4) {
            final var object = new JsonObject();
            for (var i = random.nextInt(3); i > 0; i--) {
                object.add("k" + random.nextInt(2), element(random, depth + 1));
            }
            return object;
        }
        return scalar(random);
    }

    private static JsonArray operands(Random random, int count) {
        final var array = new JsonArray();
        for (var i = 0; i < count; i++) {
            array.add(element(random, 0));
        }
        return array;
    }

    private static boolean legacyMembership(JsonArray operands, JsonObject document, String field) {
        for (final var operand : operands) {
            final var equals = new FieldOperator(FieldOperatorType.EQUALS, field, operand);
            if (FieldPredicateFactory.getTester(equals, FieldOperatorType.EQUALS).test(document, field)) {
                return true;
            }
        }
        return false;
    }

    private static void assertAgrees(String field, String testedField, Supplier<JsonBaseElement> stored,
            Random random) {
        for (var round = 0; round < 300; round++) {
            final var list = operands(random, 1 + random.nextInt(12));
            final var in = FieldPredicateFactory.getTester(new FieldOperator(FieldOperatorType.IN, field, list),
                    FieldOperatorType.IN);
            final var notIn = FieldPredicateFactory.getTester(new FieldOperator(FieldOperatorType.NOT_IN, field, list),
                    FieldOperatorType.NOT_IN);
            for (var probe = 0; probe < 20; probe++) {
                final var document = new JsonObject();
                document.add(testedField, stored.get());
                final var expected = legacyMembership(list, document, testedField);
                assertEquals(expected, in.test(document, testedField), list + " IN vs " + document);
                assertEquals(!expected, notIn.test(document, testedField), list + " NOT_IN vs " + document);
            }
        }
    }

    @Test
    public void test_membership_agrees_with_the_linear_scan_on_a_plain_field() {
        final var random = new Random(41);
        assertAgrees("plain", "plain", () -> element(random, 0), random);
    }

    @Test
    public void test_membership_agrees_with_the_linear_scan_on_the_primary_key() {
        final var random = new Random(43);
        assertAgrees("_id", "_id", () -> element(random, 0), random);
    }

    @Test
    public void test_a_tester_called_with_a_different_field_kind_still_agrees() {
        final var random = new Random(47);
        assertAgrees("plain", "_id", () -> element(random, 0), random);
        assertAgrees("_id", "plain", () -> element(random, 0), random);
    }

    @Test
    public void test_strings_compare_case_insensitively_except_on_the_primary_key() {
        final var list = new JsonArray();
        list.add(new JsonString("Straße"));
        list.add(new JsonNumber(-0.0));
        final var plain = FieldPredicateFactory.getTester(new FieldOperator(FieldOperatorType.IN, "f", list),
                FieldOperatorType.IN);
        final var primaryKey = FieldPredicateFactory.getTester(new FieldOperator(FieldOperatorType.IN, "_id", list),
                FieldOperatorType.IN);
        final var document = new JsonObject();
        document.addProperty("f", "STRAßE");
        document.addProperty("_id", "STRAßE");
        final var zero = new JsonObject();
        zero.addProperty("f", 0.0);

        assertTrue(plain.test(document, "f"));
        assertFalse(primaryKey.test(document, "_id"));
        assertTrue(plain.test(zero, "f"));
        assertFalse(plain.test(new JsonObject(), "f"));
    }

    @Test
    public void test_the_fold_hash_agrees_with_case_folded_equality() {
        for (final var left : STRINGS) {
            for (final var right : STRINGS) {
                if (CaseFolding.equal(left, right)) {
                    assertEquals(CaseFolding.foldHash(left), CaseFolding.foldHash(right), left + " / " + right);
                }
            }
        }
    }
}
