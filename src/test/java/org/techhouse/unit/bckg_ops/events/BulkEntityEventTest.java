package org.techhouse.unit.bckg_ops.events;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.data.DbEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}