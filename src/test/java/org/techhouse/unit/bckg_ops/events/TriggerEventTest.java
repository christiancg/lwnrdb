package org.techhouse.unit.bckg_ops.events;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;

public class TriggerEventTest {
    private static DbEntry entry(String id) {
        final var data = new JsonObject();
        data.addProperty("v", 1L);
        final var dbEntry = DbEntry.fromJsonObject("db", "coll", data);
        dbEntry.set_id(id);
        return dbEntry;
    }

    private static TriggerEvent event() {
        return new TriggerEvent(EventType.CREATED, "db", "coll", "audit", "recalc", false, List.of(entry("a")), "alice",
                0);
    }

    @Test
    public void test_getters() {
        final var event = event();
        assertEquals(EventType.CREATED, event.getType());
        assertEquals("db", event.getDbName());
        assertEquals("coll", event.getCollName());
        assertEquals("audit", event.getTriggerName());
        assertEquals("recalc", event.getProcedureName());
        assertFalse(event.isBatchMode());
        assertEquals(1, event.getEntries().size());
        assertEquals("alice", event.getActingUser());
        assertEquals(0, event.getDepth());
        assertTrue(event.getFiredAt() > 0);
    }

    @Test
    public void test_batch_mode_and_depth_are_carried() {
        final var batch = new TriggerEvent(EventType.UPDATED, "db", "coll", "t", "p", true,
                List.of(entry("a"), entry("b")), "bob", 2);
        assertTrue(batch.isBatchMode());
        assertEquals(2, batch.getDepth());
        assertEquals(2, batch.getEntries().size());
        assertEquals(EventType.UPDATED, batch.getType());
    }

    @Test
    public void test_equals_is_reflexive_and_matches_an_identical_event() {
        final var event = event();
        final var same = new TriggerEvent(EventType.CREATED, "db", "coll", "audit", "recalc", false,
                List.of(entry("a")), "alice", 0);
        assertEquals(event, same);
        assertEquals(event.hashCode(), same.hashCode());
    }

    @Test
    public void test_equals_honours_every_field() {
        final var event = event();
        assertNotEquals(event, new TriggerEvent(EventType.UPDATED, "db", "coll", "audit", "recalc", false,
                List.of(entry("a")), "alice", 0));
        assertNotEquals(event, new TriggerEvent(EventType.CREATED, "otherDb", "coll", "audit", "recalc", false,
                List.of(entry("a")), "alice", 0));
        assertNotEquals(event, new TriggerEvent(EventType.CREATED, "db", "otherColl", "audit", "recalc", false,
                List.of(entry("a")), "alice", 0));
        assertNotEquals(event, new TriggerEvent(EventType.CREATED, "db", "coll", "other", "recalc", false,
                List.of(entry("a")), "alice", 0));
        assertNotEquals(event, new TriggerEvent(EventType.CREATED, "db", "coll", "audit", "other", false,
                List.of(entry("a")), "alice", 0));
        assertNotEquals(event, new TriggerEvent(EventType.CREATED, "db", "coll", "audit", "recalc", true,
                List.of(entry("a")), "alice", 0));
        assertNotEquals(event, new TriggerEvent(EventType.CREATED, "db", "coll", "audit", "recalc", false,
                List.of(entry("b")), "alice", 0));
        assertNotEquals(event, new TriggerEvent(EventType.CREATED, "db", "coll", "audit", "recalc", false,
                List.of(entry("a")), "bob", 0));
        assertNotEquals(event, new TriggerEvent(EventType.CREATED, "db", "coll", "audit", "recalc", false,
                List.of(entry("a")), "alice", 1));
    }

    @Test
    public void test_not_equal_to_another_event_type_or_a_plain_object() {
        assertNotEquals(event(), new EntityEvent(EventType.CREATED, "db", "coll", entry("a")));
        assertNotEquals("not an event", event());
        assertNotEquals(null, event());
    }

    @Test
    public void test_to_string_names_the_trigger_and_reports_the_entry_count() {
        final var text = event().toString();
        assertTrue(text.contains("triggerName=audit"), text);
        assertTrue(text.contains("procedureName=recalc"), text);
        assertTrue(text.contains("entries=1"), text);
        assertTrue(text.contains("actingUser=alice"), text);
        assertTrue(text.contains("depth=0"), text);
    }
}
