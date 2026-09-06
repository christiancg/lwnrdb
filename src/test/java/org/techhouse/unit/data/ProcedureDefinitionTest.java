package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.data.ProcedureDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.utils.JsonUtils;

public class ProcedureDefinitionTest {
    private static ProcedureDefinition sample() {
        return new ProcedureDefinition("recalc", "return 1;", 3L, "a description", true, 100L, 200L, "alice");
    }

    @Test
    public void test_round_trips_through_json() {
        final var definition = sample();
        final var parsed = ProcedureDefinition.fromJsonObject(definition.toJsonObject());
        assertEquals(definition, parsed);
        assertEquals("recalc", parsed.getName());
        assertEquals("return 1;", parsed.getSource());
        assertEquals(3L, parsed.getVersion());
        assertEquals("a description", parsed.getDescription());
        assertTrue(parsed.isEnabled());
        assertEquals(100L, parsed.getCreatedAt());
        assertEquals(200L, parsed.getUpdatedAt());
        assertEquals("alice", parsed.getUpdatedBy());
    }

    @Test
    public void test_source_hash_is_the_sha256_of_the_source() {
        assertEquals(JsonUtils.sha256("return 1;"), sample().getSourceHash());
    }

    // A record written before a field existed must still load
    @Test
    public void test_reads_record_missing_optional_fields() {
        final var minimal = new JsonObject();
        minimal.add("name", new JsonString("legacy"));
        minimal.add("source", new JsonString("return 2;"));
        final var parsed = ProcedureDefinition.fromJsonObject(minimal);
        assertEquals("legacy", parsed.getName());
        assertNull(parsed.getDescription());
        assertNull(parsed.getUpdatedBy());
        assertEquals(0L, parsed.getVersion());
        // Absent reads as enabled: a record written before the flag existed was callable.
        assertTrue(parsed.isEnabled());
    }

    @Test
    public void test_disabled_flag_round_trips() {
        final var disabled = new ProcedureDefinition("p", "return 1;", 1L, null, false, 1L, 1L, "bob");
        assertFalse(ProcedureDefinition.fromJsonObject(disabled.toJsonObject()).isEnabled());
    }

    @Test
    public void test_summary_json_omits_the_source() {
        final var summary = sample().toSummaryJson();
        assertFalse(summary.has("source"));
        assertTrue(summary.has("sourceHash"));
        assertTrue(summary.has("version"));
    }

    @Test
    public void test_equals_and_hash_code_honour_every_field() {
        assertEquals(sample(), sample());
        assertEquals(sample().hashCode(), sample().hashCode());
        assertNotEquals(new ProcedureDefinition("recalc", "return 2;", 3L, "a description", true, 100L, 200L, "alice"),
                sample());
        assertNotEquals(new ProcedureDefinition("recalc", "return 1;", 4L, "a description", true, 100L, 200L, "alice"),
                sample());
        assertNotEquals("not a definition", sample());
        assertTrue(sample().toString().contains("recalc"));
    }
}
