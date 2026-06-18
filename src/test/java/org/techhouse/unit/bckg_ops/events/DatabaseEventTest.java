package org.techhouse.unit.bckg_ops.events;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.DatabaseEvent;
import org.techhouse.bckg_ops.events.EventType;

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

    @Test
    public void test_equals_same_instance() {
        DatabaseEvent event = new DatabaseEvent(EventType.CREATED, "testDB");
        assertEquals(event, event);
    }

    @Test
    public void test_equals_null_returns_false() {
        DatabaseEvent event = new DatabaseEvent(EventType.CREATED, "testDB");
        assertNotEquals(null, event);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        DatabaseEvent event = new DatabaseEvent(EventType.CREATED, "testDB");
        assertFalse(event.equals("notAnEvent"));
    }

    @Test
    public void test_equals_different_dbName_returns_false() {
        DatabaseEvent event1 = new DatabaseEvent(EventType.CREATED, "db1");
        DatabaseEvent event2 = new DatabaseEvent(EventType.CREATED, "db2");
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_equals_different_type_returns_false() {
        DatabaseEvent event1 = new DatabaseEvent(EventType.CREATED, "db");
        DatabaseEvent event2 = new DatabaseEvent(EventType.DELETED, "db");
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_hashCode_different_value_differs() {
        DatabaseEvent event1 = new DatabaseEvent(EventType.CREATED, "db1");
        DatabaseEvent event2 = new DatabaseEvent(EventType.CREATED, "db2");
        assertNotEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        DatabaseEvent event = new DatabaseEvent(EventType.CREATED, "testDB");
        assertNotNull(event.toString());
    }
}
