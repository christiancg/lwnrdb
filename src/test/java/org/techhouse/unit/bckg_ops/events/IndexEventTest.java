package org.techhouse.unit.bckg_ops.events;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.IndexEvent;

public class IndexEventTest {
    // Creating an IndexEvent with valid EventType and strings initializes all fields correctly
    @Test
    public void test_index_event_initialization() {
        EventType type = EventType.CREATED;
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";

        IndexEvent indexEvent = new IndexEvent(type, dbName, collName, fieldName);

        assertEquals(type, indexEvent.getType());
        assertEquals(dbName, indexEvent.getDbName());
        assertEquals(collName, indexEvent.getCollName());
        assertEquals(fieldName, indexEvent.getFieldName());
    }

    // Handling null values for dbName, collName, or fieldName
    @Test
    public void test_index_event_with_null_values() {
        EventType type = EventType.UPDATED;

        IndexEvent indexEvent = new IndexEvent(type, null, null, null);

        assertEquals(type, indexEvent.getType());
        assertNull(indexEvent.getDbName());
        assertNull(indexEvent.getCollName());
        assertNull(indexEvent.getFieldName());
    }

    // Test getters and setters
    @Test
    public void test_getters() {
        EventType type = EventType.CREATED;
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";
        IndexEvent indexEvent1 = new IndexEvent(type, dbName, collName, fieldName);
        assertEquals("testDB", indexEvent1.getDbName());
        assertEquals("testCollection", indexEvent1.getCollName());
        assertEquals(fieldName, indexEvent1.getFieldName());
    }

    // Test hashcode and equals
    @Test
    public void test_equals_and_hashCode() {
        EventType type = EventType.CREATED;
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";
        IndexEvent indexEvent1 = new IndexEvent(type, dbName, collName, fieldName);
        IndexEvent indexEvent2 = new IndexEvent(type, dbName, collName, fieldName);
        assertEquals(indexEvent1, indexEvent2);
        assertEquals(indexEvent1.hashCode(), indexEvent2.hashCode());
    }

    @Test
    public void test_equals_same_instance() {
        IndexEvent event = new IndexEvent(EventType.CREATED, "db", "coll", "field");
        assertEquals(event, event);
    }

    @Test
    public void test_equals_null_returns_false() {
        IndexEvent event = new IndexEvent(EventType.CREATED, "db", "coll", "field");
        assertNotEquals(null, event);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        IndexEvent event = new IndexEvent(EventType.CREATED, "db", "coll", "field");
        assertNotEquals("notAnEvent", event);
    }

    @Test
    public void test_equals_different_fieldName_returns_false() {
        IndexEvent event1 = new IndexEvent(EventType.CREATED, "db", "coll", "field1");
        IndexEvent event2 = new IndexEvent(EventType.CREATED, "db", "coll", "field2");
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_equals_different_collName_returns_false() {
        IndexEvent event1 = new IndexEvent(EventType.CREATED, "db", "coll1", "field");
        IndexEvent event2 = new IndexEvent(EventType.CREATED, "db", "coll2", "field");
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_hashCode_different_value_differs() {
        IndexEvent event1 = new IndexEvent(EventType.CREATED, "db", "coll", "field1");
        IndexEvent event2 = new IndexEvent(EventType.CREATED, "db", "coll", "field2");
        assertNotEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        IndexEvent event = new IndexEvent(EventType.CREATED, "db", "coll", "field");
        assertNotNull(event.toString());
    }
}
