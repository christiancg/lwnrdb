package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class TriggerDefinitionTimingTest {

    private static TriggerDefinition definition(String timing) {
        return new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "recalc",
                TriggerDefinition.MODE_DOCUMENT, timing, false, true, "alice", 1L, 1L, 1L, "alice");
    }

    @Test
    public void test_absent_timing_reads_as_after() {
        final var json = new JsonObject();
        json.add("name", new JsonString("audit"));
        json.add("procedureName", new JsonString("recalc"));
        final var parsed = TriggerDefinition.fromJsonObject(json);
        assertEquals(TriggerDefinition.TIMING_AFTER, parsed.getTiming());
        assertFalse(parsed.isBefore());
    }

    @Test
    public void test_default_constructor_defaults_to_after() {
        assertEquals(TriggerDefinition.TIMING_AFTER, new TriggerDefinition().getTiming());
    }

    @Test
    public void test_legacy_constructor_defaults_to_after() {
        final var legacy = new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "recalc",
                TriggerDefinition.MODE_DOCUMENT, false, true, "alice", 1L, 1L, 1L, "alice");
        assertFalse(legacy.isBefore());
    }

    @Test
    public void test_null_timing_defaults_to_after() {
        assertEquals(TriggerDefinition.TIMING_AFTER, definition(null).getTiming());
    }

    @Test
    public void test_timing_round_trips_through_json() {
        final var parsed = TriggerDefinition.fromJsonObject(definition(TriggerDefinition.TIMING_BEFORE).toJsonObject());
        assertEquals(TriggerDefinition.TIMING_BEFORE, parsed.getTiming());
        assertTrue(parsed.isBefore());
    }

    @Test
    public void test_timing_round_trips_through_the_file_shape() {
        final var file = TriggerDefinition.toFileJson(java.util.List.of(definition(TriggerDefinition.TIMING_BEFORE)));
        final var parsed = TriggerDefinition.fromFileJson(file);
        assertEquals(1, parsed.size());
        assertTrue(parsed.getFirst().isBefore());
    }

    @Test
    public void test_timing_participates_in_equality() {
        final var before = definition(TriggerDefinition.TIMING_BEFORE);
        final var after = definition(TriggerDefinition.TIMING_AFTER);
        assertNotEquals(before, after);
        assertNotEquals(before.hashCode(), after.hashCode());
        assertEquals(before, definition(TriggerDefinition.TIMING_BEFORE));
    }

    @Test
    public void test_to_string_names_the_timing() {
        assertTrue(definition(TriggerDefinition.TIMING_BEFORE).toString().contains("timing=before"));
    }
}
