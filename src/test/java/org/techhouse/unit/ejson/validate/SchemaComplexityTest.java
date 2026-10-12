package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.validate.SchemaComplexity;

public class SchemaComplexityTest {
    private static final int LIMIT = Globals.MAX_SCHEMA_EXPANDED_NODES;

    private final EJson ejson = new EJson();

    private long count(String schema) {
        return ejson.schemaExpandedNodeCount(ejson.fromJson(schema, JsonObject.class), LIMIT);
    }

    @Test
    public void test_flat_schema_counts_its_nodes() {
        assertEquals(1, count("{\"type\":\"object\"}"));
        assertEquals(5, count("{\"properties\":{\"a\":{\"type\":\"string\"},"
                + "\"b\":{\"items\":{\"type\":\"integer\"},\"not\":{\"type\":\"null\"}}}}"));
        assertEquals(4, count("{\"allOf\":[{\"minimum\":1},{\"maximum\":2}],\"if\":{\"type\":\"number\"}}"));
    }

    @Test
    public void test_ref_target_counted_once_per_reference_and_memoised() {
        assertEquals((1L << 14) - 2, count(DiamondSchemas.chain(12)));
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertEquals(LIMIT + 1L, count(DiamondSchemas.chain(40))));
    }

    @Test
    public void test_count_saturates_at_limit_plus_one() {
        assertEquals(LIMIT + 1L, count(DiamondSchemas.chain(28)));
        assertEquals(11,
                SchemaComplexity.expandedNodeCount(ejson.fromJson(DiamondSchemas.chain(28), JsonObject.class), 10));
    }

    @Test
    public void test_boolean_and_unresolvable_ref_nodes_are_tolerated() {
        assertEquals(4, count("{\"properties\":{\"a\":true,\"b\":false,\"c\":{\"$ref\":\"#/nowhere\"}}}"));
        assertEquals(2, count("{\"$ref\":\"#/$defs/a\",\"$defs\":{\"a\":{\"$ref\":\"#/$defs/a\"}}}"));
        assertEquals(1, count("{\"$ref\":42}"));
    }

    @Test
    public void test_unreferenced_defs_do_not_count() {
        assertEquals(1, count("{\"$defs\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"number\"}}}"));
    }
}
