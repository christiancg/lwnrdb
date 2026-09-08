package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class TriggerDefinitionTest {
    private static TriggerDefinition sample() {
        return new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "recalc",
                TriggerDefinition.MODE_DOCUMENT, false, true, "dbowner", 2L, 100L, 200L, "alice");
    }

    @Test
    public void test_round_trips_through_json() {
        final var parsed = TriggerDefinition.fromJsonObject(sample().toJsonObject());
        assertEquals(sample(), parsed);
        assertEquals("audit", parsed.getName());
        assertEquals(Set.of(EventType.CREATED), parsed.getEvents());
        assertEquals("recalc", parsed.getProcedureName());
        assertEquals("dbowner", parsed.getDefiner());
        assertFalse(parsed.isAllowCascade());
        assertTrue(parsed.isEnabled());
        assertFalse(parsed.isBatchMode());
        assertEquals(2L, parsed.getVersion());
        assertEquals(100L, parsed.getCreatedAt());
        assertEquals(200L, parsed.getUpdatedAt());
        assertEquals("alice", parsed.getUpdatedBy());
    }

    @Test
    public void test_parses_every_event_type() {
        final var all = new TriggerDefinition("t",
                new LinkedHashSet<>(Set.of(EventType.CREATED, EventType.UPDATED, EventType.DELETED)), "p",
                TriggerDefinition.MODE_BATCH, true, true, "owner", 1L, 1L, 1L, "owner");
        final var parsed = TriggerDefinition.fromJsonObject(all.toJsonObject());
        assertEquals(Set.of(EventType.CREATED, EventType.UPDATED, EventType.DELETED), parsed.getEvents());
        assertTrue(parsed.isBatchMode());
        assertTrue(parsed.isAllowCascade());
    }

    @Test
    public void test_unknown_event_type_is_rejected() {
        final var json = sample().toJsonObject();
        final var events = new JsonArray();
        events.add(new JsonString("EXPLODED"));
        json.add("events", events);
        assertThrows(IllegalArgumentException.class, () -> TriggerDefinition.fromJsonObject(json));
    }

    @Test
    public void test_reads_record_missing_optional_fields() {
        final var minimal = new JsonObject();
        minimal.add("name", new JsonString("legacy"));
        minimal.add("procedureName", new JsonString("p"));
        final var parsed = TriggerDefinition.fromJsonObject(minimal);
        assertEquals(TriggerDefinition.MODE_DOCUMENT, parsed.getMode());
        assertFalse(parsed.isAllowCascade());
        assertTrue(parsed.isEnabled());
        assertNull(parsed.getDefiner());
        assertTrue(parsed.getEvents().isEmpty());
    }

    @Test
    public void test_default_constructor_defaults_to_document_mode() {
        final var empty = new TriggerDefinition();
        assertEquals(TriggerDefinition.MODE_DOCUMENT, empty.getMode());
        assertFalse(empty.isBatchMode());
        assertTrue(empty.getEvents().isEmpty());
    }

    @Test
    public void test_null_events_and_mode_are_defaulted() {
        final var defaulted = new TriggerDefinition("t", null, "p", null, false, true, "o", 1L, 1L, 1L, "o");
        assertTrue(defaulted.getEvents().isEmpty());
        assertEquals(TriggerDefinition.MODE_DOCUMENT, defaulted.getMode());
    }

    @Test
    public void test_equals_and_hash_code_honour_every_field() {
        assertEquals(sample(), sample());
        assertEquals(sample().hashCode(), sample().hashCode());
        assertNotEquals(sample(), new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)),
                "recalc", TriggerDefinition.MODE_DOCUMENT, false, true, "someone-else", 2L, 100L, 200L, "alice"));
        assertNotEquals("not a definition", sample());
        assertTrue(sample().toString().contains("audit"));
    }
}
