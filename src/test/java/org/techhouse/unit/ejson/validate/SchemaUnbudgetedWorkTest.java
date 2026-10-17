package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.SchemaBudgetExceededException;

public class SchemaUnbudgetedWorkTest {
    private final EJson ejson = new EJson();

    private JsonObject json(String text) {
        return ejson.fromJson(text, JsonObject.class);
    }

    private static JsonObject numbers(int count, int offset) {
        final var array = new JsonArray();
        for (var i = 0; i < count; i++) {
            array.add(new JsonNumber(i + offset));
        }
        final var document = new JsonObject();
        document.add("v", array);
        return document;
    }

    @Test
    public void test_unique_items_over_a_large_array_is_linear() {
        final var schema = json("{\"properties\":{\"v\":{\"type\":\"array\",\"uniqueItems\":true}}}");
        final var document = numbers(200_000, 0);
        final var result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> ejson.validateInstance(document, schema, 5_000_000L));
        assertTrue(result.isValid());
    }

    @Test
    public void test_unique_items_still_finds_a_duplicate_across_number_spellings() {
        final var schema = json("{\"properties\":{\"v\":{\"type\":\"array\",\"uniqueItems\":true}}}");
        final var result = ejson.validateInstance(json("{\"v\":[1,2,3,2.0]}"), schema, 1_000L);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().getFirst().contains("unique"), result.getErrors().toString());
    }

    @Test
    public void test_unique_items_is_charged_to_the_budget() {
        final var schema = json("{\"properties\":{\"v\":{\"type\":\"array\",\"uniqueItems\":true}}}");
        assertThrows(SchemaBudgetExceededException.class,
                () -> ejson.validateInstance(numbers(50_000, 0), schema, 10_000L));
    }

    @Test
    public void test_enum_comparisons_are_charged_to_the_budget() {
        final var enumValues = new StringBuilder();
        for (var i = 0; i < 5_000; i++) {
            enumValues.append(i == 0 ? "" : ",").append(i);
        }
        final var schema = json(
                "{\"properties\":{\"v\":{\"type\":\"array\",\"items\":{\"enum\":[" + enumValues + "]}}}}");
        assertThrows(SchemaBudgetExceededException.class,
                () -> ejson.validateInstance(numbers(5_000, -10_000), schema, 100_000L));
    }

    @Test
    public void test_the_error_list_is_capped_with_a_count_of_the_rest() {
        final var schema = json("{\"properties\":{\"v\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}");
        final var result = ejson.validateInstance(numbers(1_000, 0), schema, 5_000_000L);
        assertFalse(result.isValid());
        assertEquals(101, result.getErrors().size());
        assertEquals("... and 900 more", result.getErrors().getLast());
    }

    @Test
    public void test_a_short_error_list_is_reported_unchanged() {
        final var schema = json("{\"properties\":{\"v\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}");
        final var result = ejson.validateInstance(numbers(3, 0), schema, 5_000_000L);
        assertEquals(3, result.getErrors().size());
    }

    @Test
    public void test_unique_items_charges_every_comparison_between_colliding_hashes() {
        final var schema = json("{\"properties\":{\"v\":{\"type\":\"array\",\"uniqueItems\":true}}}");
        final var colliding = new JsonArray();
        for (var mask = 0; mask < 512; mask++) {
            final var text = new StringBuilder();
            for (var bit = 0; bit < 9; bit++) {
                text.append((mask >> bit & 1) == 0 ? "Aa" : "BB");
            }
            colliding.add(new org.techhouse.ejson.elements.JsonString(text.toString()));
        }
        final var document = new JsonObject();
        document.add("v", colliding);
        assertThrows(SchemaBudgetExceededException.class, () -> ejson.validateInstance(document, schema, 50_000L));
        assertTrue(ejson.validateInstance(document, schema, 5_000_000L).isValid());
    }
}
