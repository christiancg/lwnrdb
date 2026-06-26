package org.techhouse.unit.bckg_ops.events;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;

// Event is abstract; tested via a minimal concrete subclass so the test is not coupled to any
// particular production event type. The base equals/hashCode are defined purely on the event type.
public class EventTest {

    private static final class TestEvent extends Event {
        TestEvent(EventType type) {
            super(type);
        }
    }

    @Test
    public void test_getType_returns_correct_type() {
        TestEvent event = new TestEvent(EventType.CREATED);
        assertEquals(EventType.CREATED, event.getType());
    }

    @Test
    public void test_equals_null_returns_false() {
        TestEvent event = new TestEvent(EventType.CREATED);
        assertNotEquals(null, event);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        TestEvent event = new TestEvent(EventType.CREATED);
        assertNotEquals("string", event);
    }

    @Test
    public void test_equals_same_type() {
        TestEvent event1 = new TestEvent(EventType.UPDATED);
        TestEvent event2 = new TestEvent(EventType.UPDATED);
        assertEquals(event1, event2);
    }

    @Test
    public void test_equals_different_event_type_returns_false() {
        TestEvent event1 = new TestEvent(EventType.CREATED);
        TestEvent event2 = new TestEvent(EventType.DELETED);
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_hashCode_same_values_equal() {
        TestEvent event1 = new TestEvent(EventType.CREATED);
        TestEvent event2 = new TestEvent(EventType.CREATED);
        assertEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_hashCode_different_type_differs() {
        TestEvent event1 = new TestEvent(EventType.CREATED);
        TestEvent event2 = new TestEvent(EventType.DELETED);
        assertNotEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        TestEvent event = new TestEvent(EventType.CREATED);
        assertNotNull(event.toString());
    }
}
