package org.techhouse.unit.bckg_ops.events;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.data.DbEntry;

public class BulkEntityEventTest {
    // Creating a BulkEntityEvent with valid dbName, collName, and non-empty lists of DbEntry
    @Test
    public void test_bulk_entity_event_with_valid_entries() {
        String dbName = "testDB";
        String collName = "testCollection";
        DbEntry entry1 = new DbEntry();
        DbEntry entry2 = new DbEntry();
        List<DbEntry> insertedEntries = List.of(entry1);
        List<DbEntry> updatedEntries = List.of(entry2);

        BulkEntityEvent event = new BulkEntityEvent(dbName, collName, insertedEntries, updatedEntries);

        assertEquals(dbName, event.getDbName());
        assertEquals(collName, event.getCollName());
        assertEquals(insertedEntries, event.getInsertedEntries());
        assertEquals(updatedEntries, event.getUpdatedEntries());
    }

    // Creating a BulkEntityEvent with empty lists for insertedEntries and updatedEntries
    @Test
    public void test_bulk_entity_event_with_empty_entries() {
        String dbName = "testDB";
        String collName = "testCollection";
        List<DbEntry> insertedEntries = List.of();
        List<DbEntry> updatedEntries = List.of();

        BulkEntityEvent event = new BulkEntityEvent(dbName, collName, insertedEntries, updatedEntries);

        assertEquals(dbName, event.getDbName());
        assertEquals(collName, event.getCollName());
        assertTrue(event.getInsertedEntries().isEmpty());
        assertTrue(event.getUpdatedEntries().isEmpty());
    }

    // Test getters and setters
    @Test
    public void test_getters() {
        List<DbEntry> insertedEntries = List.of();
        List<DbEntry> updatedEntries = List.of();

        BulkEntityEvent event = new BulkEntityEvent("testDB", "testCollection", insertedEntries, updatedEntries);
        assertEquals("testDB", event.getDbName());
        assertEquals("testCollection", event.getCollName());
        assertEquals(insertedEntries, event.getInsertedEntries());
        assertEquals(updatedEntries, event.getUpdatedEntries());
    }

    // Test hashcode and equals
    @Test
    public void test_equals_and_hashCode() {
        List<DbEntry> insertedEntries = List.of();
        List<DbEntry> updatedEntries = List.of();
        BulkEntityEvent event1 = new BulkEntityEvent("testDB", "testCollection", insertedEntries, updatedEntries);
        BulkEntityEvent event2 = new BulkEntityEvent("testDB", "testCollection", insertedEntries, updatedEntries);
        assertEquals(event1, event2);
        assertEquals(event2.hashCode(), event1.hashCode());
    }

    @Test
    public void test_equals_same_instance() {
        BulkEntityEvent event = new BulkEntityEvent("db", "coll", List.of(), List.of());
        assertEquals(event, event);
    }

    @Test
    public void test_equals_null_returns_false() {
        BulkEntityEvent event = new BulkEntityEvent("db", "coll", List.of(), List.of());
        assertNotEquals(null, event);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        BulkEntityEvent event = new BulkEntityEvent("db", "coll", List.of(), List.of());
        assertFalse(event.equals("notAnEvent"));
    }

    @Test
    public void test_equals_different_dbName_returns_false() {
        BulkEntityEvent event1 = new BulkEntityEvent("db1", "coll", List.of(), List.of());
        BulkEntityEvent event2 = new BulkEntityEvent("db2", "coll", List.of(), List.of());
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_equals_different_collName_returns_false() {
        BulkEntityEvent event1 = new BulkEntityEvent("db", "coll1", List.of(), List.of());
        BulkEntityEvent event2 = new BulkEntityEvent("db", "coll2", List.of(), List.of());
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_hashCode_different_value_differs() {
        BulkEntityEvent event1 = new BulkEntityEvent("db1", "coll", List.of(), List.of());
        BulkEntityEvent event2 = new BulkEntityEvent("db2", "coll", List.of(), List.of());
        assertNotEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        BulkEntityEvent event = new BulkEntityEvent("db", "coll", List.of(), List.of());
        assertNotNull(event.toString());
    }
}
