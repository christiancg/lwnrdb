package org.techhouse.unit.bckg_ops.events;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EntityEvent;

import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EntityEventTest {
    // Creating an EntityEvent with valid EventType, dbName, collName, and DbEntry
    @Test
    public void test_create_entity_event_with_valid_parameters() {
        EventType type = EventType.CREATED;
        String dbName = "testDatabase";
        String collName = "testCollection";
        JsonObject jsonObject = new JsonObject();
        DbEntry dbEntry = DbEntry.fromJsonObject(dbName, collName, jsonObject);
    
        EntityEvent entityEvent = new EntityEvent(type, dbName, collName, dbEntry);
    
        assertEquals(type, entityEvent.getType());
        assertEquals(dbName, entityEvent.getDbName());
        assertEquals(collName, entityEvent.getCollName());
        assertEquals(dbEntry, entityEvent.getDbEntry());
    }

    // Test getters and setters
    @Test
    public void test_getters() {
        EventType type = EventType.CREATED;
        String dbName = "testDatabase";
        String collName = "testCollection";
        JsonObject jsonObject = new JsonObject();
        DbEntry dbEntry = DbEntry.fromJsonObject(dbName, collName, jsonObject);
        EntityEvent entityEvent = new EntityEvent(type, dbName, collName, dbEntry);
        assertEquals("testDatabase", entityEvent.getDbName());
        assertEquals("testCollection", entityEvent.getCollName());
        assertEquals(dbEntry, entityEvent.getDbEntry());
    }

    // Test hashcode and equals
    @Test
    public void test_equals_and_hashCode() {
        EventType type = EventType.CREATED;
        String dbName = "testDatabase";
        String collName = "testCollection";
        JsonObject jsonObject = new JsonObject();
        DbEntry dbEntry = DbEntry.fromJsonObject(dbName, collName, jsonObject);
        EntityEvent entityEvent1 = new EntityEvent(type, dbName, collName, dbEntry);
        EntityEvent entityEvent2 = new EntityEvent(type, dbName, collName, dbEntry);
        assertEquals(entityEvent1, entityEvent2);
        assertEquals(entityEvent1.hashCode(), entityEvent2.hashCode());
    }
}