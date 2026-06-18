package org.techhouse.unit.bckg_ops.events;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.UsageProfileCleanupEvent;

public class UsageProfileCleanupEventTest {

    @Test
    public void test_create_event() {
        final var event = new UsageProfileCleanupEvent();
        assertEquals(EventType.DELETED, event.getType());
    }

    @Test
    public void test_equals_and_hashCode() {
        final var e1 = new UsageProfileCleanupEvent();
        final var e2 = new UsageProfileCleanupEvent();
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    public void test_equals_same_instance() {
        final var e = new UsageProfileCleanupEvent();
        assertEquals(e, e);
    }

    @Test
    public void test_equals_null_returns_false() {
        final var e = new UsageProfileCleanupEvent();
        assertNotEquals(null, e);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        final var e = new UsageProfileCleanupEvent();
        assertFalse(e.equals("string"));
    }

    @Test
    public void test_toString_not_null() {
        final var e = new UsageProfileCleanupEvent();
        assertNotNull(e.toString());
    }
}
