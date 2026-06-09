package org.techhouse.unit.bckg_ops.events;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EntityEvent;

import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    public void test_equals_same_instance() {
        DbEntry dbEntry = DbEntry.fromJsonObject("db", "coll", new JsonObject());
        EntityEvent event = new EntityEvent(EventType.CREATED, "db", "coll", dbEntry);
        assertEquals(event, event);
    }

    @Test
    public void test_equals_null_returns_false() {
        DbEntry dbEntry = DbEntry.fromJsonObject("db", "coll", new JsonObject());
        EntityEvent event = new EntityEvent(EventType.CREATED, "db", "coll", dbEntry);
        assertNotEquals(null, event);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        DbEntry dbEntry = DbEntry.fromJsonObject("db", "coll", new JsonObject());
        EntityEvent event = new EntityEvent(EventType.CREATED, "db", "coll", dbEntry);
        assertNotEquals("notAnEvent", event);
    }

    @Test
    public void test_equals_different_collName_returns_false() {
        DbEntry dbEntry = DbEntry.fromJsonObject("db", "coll", new JsonObject());
        EntityEvent event1 = new EntityEvent(EventType.CREATED, "db", "coll1", dbEntry);
        EntityEvent event2 = new EntityEvent(EventType.CREATED, "db", "coll2", dbEntry);
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_hashCode_different_value_differs() {
        DbEntry dbEntry1 = DbEntry.fromJsonObject("db", "coll1", new JsonObject());
        DbEntry dbEntry2 = DbEntry.fromJsonObject("db", "coll2", new JsonObject());
        EntityEvent event1 = new EntityEvent(EventType.CREATED, "db", "coll1", dbEntry1);
        EntityEvent event2 = new EntityEvent(EventType.CREATED, "db", "coll2", dbEntry2);
        assertNotEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        DbEntry dbEntry = DbEntry.fromJsonObject("db", "coll", new JsonObject());
        EntityEvent event = new EntityEvent(EventType.CREATED, "db", "coll", dbEntry);
        assertNotNull(event.toString());
    }
}