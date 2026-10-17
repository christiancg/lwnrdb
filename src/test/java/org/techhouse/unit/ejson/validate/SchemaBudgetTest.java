package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.exceptions.SchemaBudgetExceededException;
import org.techhouse.ejson.validate.JsonSchema;
import org.techhouse.ejson.validate.SchemaValidator;

public class SchemaBudgetTest {
    private static final Duration GENEROUS = Duration.ofSeconds(20);
    private static final long DEFAULT_BUDGET = Long.parseLong(ConfigKey.SCHEMA_VALIDATION_BUDGET.defaultValue());

    private final EJson ejson = new EJson();

    private JsonObject json(String text) {
        return ejson.fromJson(text, JsonObject.class);
    }

    private static JsonObject named(String name) {
        final var doc = new JsonObject();
        doc.add("name", new JsonString(name));
        return doc;
    }

    private void assertRefused(JsonBaseElement instance, String schema) {
        assertTimeoutPreemptively(GENEROUS, () -> assertThrows(SchemaBudgetExceededException.class,
                () -> ejson.validateInstance(instance, json(schema), DEFAULT_BUDGET)));
    }

    @Test
    public void test_pattern_redos_is_refused_with_budget_exception() {
        assertRefused(named(DiamondSchemas.hostileName()), DiamondSchemas.redosPatternSchema());
        assertTrue(ejson.validateInstance(named("xxy"), json(DiamondSchemas.redosPatternSchema()), DEFAULT_BUDGET)
                .isValid());
    }

    @Test
    public void test_pattern_properties_redos_is_refused() {
        final var doc = new JsonObject();
        doc.add(DiamondSchemas.hostileName(), new JsonNumber(1));
        assertRefused(doc, "{\"patternProperties\":{\"(x+x+)+y\":{\"type\":\"number\"}}}");
    }

    @Test
    public void test_property_names_pattern_redos_is_refused() {
        final var doc = new JsonObject();
        doc.add(DiamondSchemas.hostileName(), new JsonNumber(1));
        assertRefused(doc, "{\"propertyNames\":{\"pattern\":\"(x+x+)+y\"}}");
    }

    @Test
    public void test_allof_ref_diamond_chain_of_28_is_refused_not_hung() {
        assertRefused(new JsonObject(), DiamondSchemas.chain(28));
    }

    @Test
    public void test_small_chain_below_budget_still_validates() {
        final var result = ejson.validateInstance(new JsonObject(), json(DiamondSchemas.chain(10)), DEFAULT_BUDGET);
        assertTrue(result.isValid());
        assertFalse(ejson.validateInstance(new JsonArray(), json(DiamondSchemas.chain(10)), DEFAULT_BUDGET).isValid());
    }

    @Test
    public void test_legit_large_document_fits_default_budget() {
        final var wide = new JsonObject();
        for (var i = 0; i < 20_000; i++) {
            wide.add("p" + i, new JsonString("Lorem ipsum dolor sit amet consectetur"));
        }
        final var wideSchema = json("{\"type\":\"object\",\"propertyNames\":{\"pattern\":\"^p[0-9]+$\"},"
                + "\"additionalProperties\":{\"type\":\"string\",\"pattern\":\"^[a-zA-Z0-9 ]{1,64}$\"}}");
        assertTrue(ejson.validateInstance(wide, wideSchema, DEFAULT_BUDGET / 5).isValid());

        final var items = new JsonArray();
        for (var i = 0; i < 100_000; i++) {
            items.add(new JsonNumber(i));
        }
        final var holder = new JsonObject();
        holder.add("items", items);
        final var arraySchema = json(
                "{\"properties\":{\"items\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}}}");
        assertTrue(ejson.validateInstance(holder, arraySchema, DEFAULT_BUDGET / 5).isValid());
    }

    @Test
    public void test_budget_exactly_equal_to_the_steps_needed_succeeds_and_one_fewer_fails() {
        final var schema = json("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
        final var doc = new JsonObject();
        doc.add("a", new JsonString("x"));
        assertTrue(ejson.validateInstance(doc, schema, 2).isValid());
        assertThrows(SchemaBudgetExceededException.class, () -> ejson.validateInstance(doc, schema, 1));
    }

    @Test
    public void test_budget_is_per_validation_not_shared() {
        final var schema = json("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
        final var doc = new JsonObject();
        doc.add("a", new JsonString("x"));
        final var validator = new SchemaValidator();
        assertTrue(validator.validate(doc, new JsonSchema(schema, 3)).isValid());
        assertTrue(validator.validate(doc, new JsonSchema(schema, 3)).isValid());
    }

    @Test
    public void test_applicators_share_the_validations_budget() {
        final var schema = json(
                "{\"anyOf\":[{\"not\":{\"type\":\"string\"}},{\"type\":\"null\"},{\"type\":\"null\"}]}");
        final var instance = new JsonString("s");
        assertFalse(ejson.validateInstance(instance, schema, 5).isValid());
        assertThrows(SchemaBudgetExceededException.class, () -> ejson.validateInstance(instance, schema, 4));
    }

    @Test
    public void test_pattern_on_a_non_string_charges_no_regex_steps() {
        assertTrue(ejson.validateInstance(new JsonNumber(5), json("{\"pattern\":\"(x+x+)+y\"}"), 1).isValid());
    }

    @Test
    public void test_surrogate_pair_input_through_the_budgeted_pattern() {
        assertTrue(ejson.validateInstance(new JsonString("😀"), json("{\"pattern\":\"^.$\"}"), 1000).isValid());
    }

    @Test
    public void test_concurrent_validations_do_not_share_state() throws Exception {
        final var validator = new SchemaValidator();
        final var schema = json(DiamondSchemas.redosPatternSchema());
        try (var pool = Executors.newFixedThreadPool(8)) {
            final var futures = new ArrayList<Future<Boolean>>();
            futures.add(pool.submit(() -> {
                assertThrows(SchemaBudgetExceededException.class, () -> validator
                        .validate(named(DiamondSchemas.hostileName()), new JsonSchema(schema, DEFAULT_BUDGET)));
                return true;
            }));
            for (var i = 0; i < 7; i++) {
                final Callable<Boolean> cheap = () -> {
                    var valid = true;
                    for (var round = 0; round < 200; round++) {
                        valid &= validator.validate(named("xxxy"), new JsonSchema(schema, 10_000)).isValid();
                    }
                    return valid;
                };
                futures.add(pool.submit(cheap));
            }
            for (final var future : futures) {
                assertTrue(future.get());
            }
        }
    }

    @Test
    public void test_unlimited_one_arg_constructor_never_throws() {
        final var schema = json(DiamondSchemas.chain(12));
        final var validator = new SchemaValidator();
        assertThrows(SchemaBudgetExceededException.class,
                () -> validator.validate(new JsonObject(), new JsonSchema(schema, 1000)));
        assertDoesNotThrow(() -> validator.validate(new JsonObject(), new JsonSchema(schema)));
        assertTrue(ejson.validateInstance(new JsonObject(), schema).isValid());
    }

    @Test
    public void test_the_budget_exception_is_not_an_invalid_schema() {
        final var refused = assertThrows(SchemaBudgetExceededException.class,
                () -> ejson.validateInstance(new JsonObject(), json(DiamondSchemas.chain(28)), 1000));
        assertEquals("Schema validation exceeded its evaluation budget", refused.getMessage());
    }
}
