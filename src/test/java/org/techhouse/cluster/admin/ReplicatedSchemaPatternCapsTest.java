package org.techhouse.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.StoredDefinitions;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReplicatedSchemaPatternCapsTest extends AdminRecordTestBase {
    private static final String LONG_PATTERN = "^replicated-over-cap-[a-z]+$";
    private final EJson eJson = IocContainer.get(EJson.class);

    @AfterEach
    void restoreDefaults() throws Exception {
        TestUtils.setPrivateField(config, "schemaMaxPatternLength", ConfigKey.SCHEMA_MAX_PATTERN_LENGTH.defaultValue());
        TestUtils.setPrivateField(config, "schemaMaxPatterns", ConfigKey.SCHEMA_MAX_PATTERNS.defaultValue());
    }

    private AdminRecord overCapSchema(long version) {
        final var pattern = new JsonObject();
        pattern.add("pattern", new JsonString(LONG_PATTERN));
        final var other = new JsonObject();
        other.add("pattern", new JsonString("^b$"));
        final var properties = new JsonObject();
        properties.add("name", pattern);
        properties.add("other", other);
        final var schema = new JsonObject();
        schema.add("properties", properties);
        final var body = new JsonObject();
        body.add("schema", schema);
        body.addProperty("writeVersion", Long.toString(version));
        return new AdminRecord(AdminRecordKey.schema(TestGlobals.DB, TestGlobals.COLL), version, body);
    }

    private static Map<?, ?> compiledPatterns() throws Exception {
        return TestUtils.getPrivateStaticField(Class.forName("org.techhouse.ejson.validate.SchemaPatterns"), "COMPILED",
                Map.class);
    }

    @Test
    public void test_a_replicated_schema_over_the_caps_is_stored_but_its_long_pattern_is_not_cached() throws Exception {
        TestUtils.setPrivateField(config, "schemaMaxPatternLength", 8);
        TestUtils.setPrivateField(config, "schemaMaxPatterns", 1);

        final var result = AdminRecordMerge.apply(List.of(overCapSchema(clock.next())), true);

        assertTrue(result.complete());
        assertNotNull(StoredDefinitions.schema(TestGlobals.DB, TestGlobals.COLL));
        final var schema = cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(schema);
        final var document = new JsonObject();
        document.add("name", new JsonString("replicated-over-cap-abc"));
        assertTrue(eJson.validateWithSchema(document, schema).isValid());
        document.add("name", new JsonString("replicated-over-cap-123"));
        assertFalse(eJson.validateWithSchema(document, schema).isValid());
        assertFalse(compiledPatterns().containsKey(LONG_PATTERN));
    }
}
