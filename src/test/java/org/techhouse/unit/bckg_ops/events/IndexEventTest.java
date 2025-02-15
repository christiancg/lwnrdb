package org.techhouse.unit.bckg_ops.events;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.IndexEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}