package org.techhouse.unit.bckg_ops.events;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.DatabaseEvent;
import org.techhouse.bckg_ops.events.EventType;

// Event is abstract; tested via DatabaseEvent which extends it.
public class EventTest {

    @Test
    public void test_getType_returns_correct_type() {
        DatabaseEvent event = new DatabaseEvent(EventType.CREATED, "db");
        assertEquals(EventType.CREATED, event.getType());
    }

    @Test
    public void test_equals_same_instance() {
        DatabaseEvent event = new DatabaseEvent(EventType.CREATED, "db");
        assertEquals(event, event);
    }

    @Test
    public void test_equals_null_returns_false() {
        DatabaseEvent event = new DatabaseEvent(EventType.CREATED, "db");
        assertNotEquals(null, event);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        DatabaseEvent event = new DatabaseEvent(EventType.CREATED, "db");
        assertNotEquals("string", event);
    }

    @Test
    public void test_equals_same_type_and_db() {
        DatabaseEvent event1 = new DatabaseEvent(EventType.UPDATED, "db");
        DatabaseEvent event2 = new DatabaseEvent(EventType.UPDATED, "db");
        assertEquals(event1, event2);
    }

    @Test
    public void test_equals_different_event_type_returns_false() {
        DatabaseEvent event1 = new DatabaseEvent(EventType.CREATED, "db");
        DatabaseEvent event2 = new DatabaseEvent(EventType.DELETED, "db");
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_hashCode_same_values_equal() {
        DatabaseEvent event1 = new DatabaseEvent(EventType.CREATED, "db");
        DatabaseEvent event2 = new DatabaseEvent(EventType.CREATED, "db");
        assertEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_hashCode_different_type_differs() {
        DatabaseEvent event1 = new DatabaseEvent(EventType.CREATED, "db");
        DatabaseEvent event2 = new DatabaseEvent(EventType.DELETED, "db");
        assertNotEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        DatabaseEvent event = new DatabaseEvent(EventType.CREATED, "db");
        assertNotNull(event.toString());
    }
}
