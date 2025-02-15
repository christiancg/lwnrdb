package org.techhouse.unit.bckg_ops.events;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.DatabaseEvent;
import org.techhouse.bckg_ops.events.EventType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DatabaseEventTest {
    // Creating a DatabaseEvent with valid EventType and dbName
    @Test
    public void test_create_database_event_with_valid_inputs() {
        EventType eventType = EventType.CREATED;
        String dbName = "testDB";
        DatabaseEvent databaseEvent = new DatabaseEvent(eventType, dbName);
        assertEquals(eventType, databaseEvent.getType());
        assertEquals(dbName, databaseEvent.getDbName());
    }

    // Creating a DatabaseEvent with null dbName
    @Test
    public void test_create_database_event_with_null_dbname() {
        EventType eventType = EventType.CREATED;
        String dbName = null;
        DatabaseEvent databaseEvent = new DatabaseEvent(eventType, dbName);
        assertEquals(eventType, databaseEvent.getType());
        assertNull(databaseEvent.getDbName());
    }

    // Test getters and setters
    @Test
    public void test_getters() {
        EventType eventType = EventType.CREATED;
        String dbName = "testDB";
        DatabaseEvent databaseEvent = new DatabaseEvent(eventType, dbName);
        assertEquals("testDB", databaseEvent.getDbName());
    }

    // Test hashcode and equals
    @Test
    public void test_equals_and_hashCode() {
        EventType eventType = EventType.CREATED;
        String dbName = "testDB";
        DatabaseEvent databaseEvent1 = new DatabaseEvent(eventType, dbName);
        DatabaseEvent databaseEvent2 = new DatabaseEvent(eventType, dbName);
        assertEquals(databaseEvent1, databaseEvent2);
        assertEquals(databaseEvent1.hashCode(), databaseEvent2.hashCode());
    }
}