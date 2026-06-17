package org.techhouse.unit.bckg_ops.events;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.AccessKind;

import static org.junit.jupiter.api.Assertions.*;

public class CollectionUsageEventTest {

    @Test
    public void test_create_event_with_valid_parameters() {
        final var event = new CollectionUsageEvent(AccessKind.COLLECTION, "db", "coll", "field", 123L);
        assertEquals(EventType.UPDATED, event.getType());
        assertEquals(AccessKind.COLLECTION, event.getKind());
        assertEquals("db", event.getDbName());
        assertEquals("coll", event.getCollName());
        assertEquals("field", event.getIndexKey());
        assertEquals(123L, event.getTimestampMillis());
    }

    @Test
    public void test_null_indexKey_normalized_to_empty() {
        final var event = new CollectionUsageEvent(AccessKind.PK_INDEX, "db", "coll", null, 1L);
        assertEquals("", event.getIndexKey());
    }

    @Test
    public void test_equals_and_hashCode() {
        final var e1 = new CollectionUsageEvent(AccessKind.COLLECTION, "db", "coll", null, 1L);
        final var e2 = new CollectionUsageEvent(AccessKind.COLLECTION, "db", "coll", null, 1L);
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    public void test_equals_same_instance() {
        final var e = new CollectionUsageEvent(AccessKind.COLLECTION, "db", "coll", null, 1L);
        assertEquals(e, e);
    }

    @Test
    public void test_equals_null_returns_false() {
        final var e = new CollectionUsageEvent(AccessKind.COLLECTION, "db", "coll", null, 1L);
        assertNotEquals(null, e);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        final var e = new CollectionUsageEvent(AccessKind.COLLECTION, "db", "coll", null, 1L);
        assertNotEquals("string", e);
    }

    @Test
    public void test_equals_different_kind() {
        final var e1 = new CollectionUsageEvent(AccessKind.COLLECTION, "db", "coll", null, 1L);
        final var e2 = new CollectionUsageEvent(AccessKind.PK_INDEX, "db", "coll", null, 1L);
        assertNotEquals(e1, e2);
    }

    @Test
    public void test_equals_different_timestamp() {
        final var e1 = new CollectionUsageEvent(AccessKind.COLLECTION, "db", "coll", null, 1L);
        final var e2 = new CollectionUsageEvent(AccessKind.COLLECTION, "db", "coll", null, 2L);
        assertNotEquals(e1, e2);
    }

    @Test
    public void test_toString_not_null() {
        final var e = new CollectionUsageEvent(AccessKind.COLLECTION, "db", "coll", null, 1L);
        assertNotNull(e.toString());
    }
}
