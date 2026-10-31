package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.validate.SchemaBudget;

public class SchemaErrorPathTest {
    private static final String ID = "_id";
    private static final long BUDGET = 1_000_000;
    private final EJson ejson = new EJson();

    private JsonObject json(String text) {
        return ejson.fromJson(text, JsonObject.class);
    }

    private List<String> errors(String schema, String document) {
        return ejson.validateInstance(json(document), json(schema), BUDGET).getErrors();
    }

    private static JsonObject withoutId(JsonObject object) {
        final var copy = new JsonObject();
        for (final var entry : object.entrySet()) {
            if (!ID.equals(entry.getKey())) {
                copy.add(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private static long remaining(SchemaBudget budget) throws Exception {
        final var field = SchemaBudget.class.getDeclaredField("remaining");
        field.setAccessible(true);
        return field.getLong(budget);
    }

    @Test
    public void test_error_paths_name_every_segment_and_the_root() {
        assertEquals(List.of("<root>: missing required property 'name'"),
                errors("{\"required\":[\"name\"]}", "{\"other\":1}"));
        assertEquals(List.of("/address/zip: string does not match the required pattern"),
                errors("{\"properties\":{\"address\":{\"properties\":{\"zip\":{\"pattern\":\"^[0-9]{5}$\"}}}}}",
                        "{\"address\":{\"zip\":\"12a45\"}}"));
        assertEquals(
                List.of("/tags/1: value does not match the required type",
                        "/tags/3: value does not match the required type"),
                errors("{\"properties\":{\"tags\":{\"items\":{\"type\":\"string\"}}}}",
                        "{\"tags\":[\"a\",1,\"b\",false]}"));
        assertEquals(List.of("/pair/0: value does not match the required type"),
                errors("{\"properties\":{\"pair\":{\"prefixItems\":[{\"type\":\"number\"},{\"type\":\"string\"}]}}}",
                        "{\"pair\":[\"x\",\"y\"]}"));
        assertEquals(List.of("/long-key: string is longer than 3 characters"),
                errors("{\"propertyNames\":{\"maxLength\":3}}", "{\"ok\":1,\"long-key\":2}"));
        assertEquals(List.of("/: no value is allowed here"), errors("{\"additionalProperties\":false}", "{\"\":1}"));
        assertEquals(List.of("/rows/0/cells/2/v: value is greater than the maximum"),
                errors("{\"properties\":{\"rows\":{\"items\":{\"properties\":{\"cells\":{\"items\":"
                        + "{\"properties\":{\"v\":{\"maximum\":5}}}}}}}}}",
                        "{\"rows\":[{\"cells\":[{\"v\":1},{\"v\":2},{\"v\":9}]}]}"));
        assertEquals(List.of("/a~b/c/d: value does not equal the required const"),
                errors("{\"properties\":{\"a~b\":{\"properties\":{\"c/d\":{\"const\":1}}}}}", "{\"a~b\":{\"c/d\":2}}"));
    }

    @Test
    public void test_hiding_the_id_answers_exactly_as_validating_a_copy_without_it() throws Exception {
        final var schemas = List.of("{\"required\":[\"_id\",\"name\"]}",
                "{\"additionalProperties\":false,\"properties\":{\"name\":{},\"nested\":{}}}",
                "{\"propertyNames\":{\"maxLength\":2}}", "{\"minProperties\":2}", "{\"maxProperties\":1}",
                "{\"dependentRequired\":{\"_id\":[\"name\"],\"name\":[\"_id\"]}}",
                "{\"dependentSchemas\":{\"_id\":false,\"name\":{\"required\":[\"_id\"]}}}",
                "{\"const\":{\"name\":\"a\"}}", "{\"enum\":[{\"name\":\"a\"},{\"_id\":\"x\",\"name\":\"b\"}]}",
                "{\"allOf\":[{\"required\":[\"_id\"]}],\"anyOf\":[{\"required\":[\"_id\"]},{\"maxProperties\":0}],"
                        + "\"not\":{\"required\":[\"name\"]}}",
                "{\"$ref\":\"#/$defs/d\",\"$defs\":{\"d\":{\"required\":[\"_id\"],\"minProperties\":1}}}",
                "{\"if\":{\"required\":[\"_id\"]},\"then\":false,\"else\":{\"properties\":{\"name\":{\"const\":\"a\"}}}}",
                "{\"patternProperties\":{\"^_\":false}}",
                "{\"properties\":{\"nested\":{\"required\":[\"_id\"],\"additionalProperties\":false}}}",
                "{\"oneOf\":[{\"properties\":{\"_id\":false}},{\"required\":[\"name\"]}]}");
        final var documents = List.of("{\"_id\":\"x\",\"name\":\"a\"}", "{\"_id\":\"x\"}", "{\"name\":\"a\"}",
                "{\"_id\":\"x\",\"name\":\"b\",\"nested\":{\"_id\":\"y\"}}", "{\"_id\":\"x\",\"nested\":{}}", "{}");
        var invalid = 0;
        for (final var schemaText : schemas) {
            final var schema = json(schemaText);
            for (final var documentText : documents) {
                final var document = json(documentText);
                final var copyBudget = new SchemaBudget(BUDGET);
                final var hiddenBudget = new SchemaBudget(BUDGET);
                final var expected = ejson.validateInstance(withoutId(document), schema, copyBudget);
                final var actual = ejson.validateInstanceWithout(document, ID, schema, hiddenBudget);
                final var label = schemaText + " on " + documentText;
                assertEquals(expected.isValid(), actual.isValid(), label);
                assertEquals(expected.getErrors(), actual.getErrors(), label);
                assertEquals(remaining(copyBudget), remaining(hiddenBudget), label);
                assertEquals(document, json(documentText), label);
                invalid += expected.isValid() ? 0 : 1;
            }
        }
        assertTrue(invalid > 20, "invalid cases " + invalid);
    }
}
