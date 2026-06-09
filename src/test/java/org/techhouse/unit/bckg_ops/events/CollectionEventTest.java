package org.techhouse.unit.bckg_ops.events;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.CollectionEvent;
import org.techhouse.bckg_ops.events.EventType;

import static org.junit.jupiter.api.Assertions.*;

public class CollectionEventTest {

    @Test
    public void test_create_collection_event_with_valid_inputs() {
        CollectionEvent event = new CollectionEvent(EventType.CREATED, "testDB", "testColl");
        assertEquals(EventType.CREATED, event.getType());
        assertEquals("testDB", event.getDbName());
        assertEquals("testColl", event.getCollName());
    }

    @Test
    public void test_equals_same_instance() {
        CollectionEvent event = new CollectionEvent(EventType.CREATED, "db", "coll");
        assertEquals(event, event);
    }

    @Test
    public void test_equals_symmetric() {
        CollectionEvent event1 = new CollectionEvent(EventType.CREATED, "db", "coll");
        CollectionEvent event2 = new CollectionEvent(EventType.CREATED, "db", "coll");
        assertEquals(event1, event2);
        assertEquals(event2, event1);
    }

    @Test
    public void test_equals_null_returns_false() {
        CollectionEvent event = new CollectionEvent(EventType.CREATED, "db", "coll");
        assertNotEquals(null, event);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        CollectionEvent event = new CollectionEvent(EventType.CREATED, "db", "coll");
        assertNotEquals("notAnEvent", event);
    }

    @Test
    public void test_equals_different_dbName_returns_false() {
        CollectionEvent event1 = new CollectionEvent(EventType.CREATED, "db1", "coll");
        CollectionEvent event2 = new CollectionEvent(EventType.CREATED, "db2", "coll");
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_equals_different_collName_returns_false() {
        CollectionEvent event1 = new CollectionEvent(EventType.CREATED, "db", "coll1");
        CollectionEvent event2 = new CollectionEvent(EventType.CREATED, "db", "coll2");
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_equals_different_type_returns_false() {
        CollectionEvent event1 = new CollectionEvent(EventType.CREATED, "db", "coll");
        CollectionEvent event2 = new CollectionEvent(EventType.DELETED, "db", "coll");
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_hashCode_same_values_equal() {
        CollectionEvent event1 = new CollectionEvent(EventType.CREATED, "db", "coll");
        CollectionEvent event2 = new CollectionEvent(EventType.CREATED, "db", "coll");
        assertEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_hashCode_different_value_differs() {
        CollectionEvent event1 = new CollectionEvent(EventType.CREATED, "db", "coll1");
        CollectionEvent event2 = new CollectionEvent(EventType.CREATED, "db", "coll2");
        assertNotEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        CollectionEvent event = new CollectionEvent(EventType.CREATED, "db", "coll");
        assertNotNull(event.toString());
    }
}
