package org.techhouse.unit.data.admin;

import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminDbEntry;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AdminDbEntryOwnersTest {
    @Test
    public void test_constructor_with_owners() {
        final var entry = new AdminDbEntry("mydb", new ArrayList<>(), List.of("alice", "bob"));
        assertEquals(List.of("alice", "bob"), entry.getOwners());
        assertTrue(entry.isOwner("alice"));
        assertFalse(entry.isOwner("charlie"));
    }

    @Test
    public void test_set_owners_updates_data() {
        final var entry = new AdminDbEntry("mydb");
        entry.setOwners(List.of("alice"));
        assertEquals(List.of("alice"), entry.getOwners());
        assertTrue(entry.isOwner("alice"));
    }

    @Test
    public void test_get_owners_default_empty() {
        final var entry = new AdminDbEntry("mydb");
        assertNotNull(entry.getOwners());
        assertTrue(entry.getOwners().isEmpty());
    }

    @Test
    public void test_from_json_round_trip_with_owners() {
        final var original = new AdminDbEntry("mydb", new ArrayList<>(), List.of("alice", "bob"));
        final var restored = AdminDbEntry.fromJsonObject(original.getData());
        assertEquals(original.getOwners(), restored.getOwners());
        assertTrue(restored.isOwner("alice"));
    }

    @Test
    public void test_from_json_backward_compat_no_owners_field() {
        // Simulate old entry without owners field
        final var original = new AdminDbEntry("olddb");
        original.getData().remove("owners");
        final var restored = AdminDbEntry.fromJsonObject(original.getData());
        assertNotNull(restored.getOwners());
        assertTrue(restored.getOwners().isEmpty());
    }

    @Test
    public void test_equals_and_hashcode_with_owners() {
        final var e1 = new AdminDbEntry("mydb", new ArrayList<>(), List.of("alice"));
        final var e2 = new AdminDbEntry("mydb", new ArrayList<>(), List.of("alice"));
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    public void test_toString_includes_owners() {
        final var entry = new AdminDbEntry("mydb", new ArrayList<>(), List.of("alice"));
        assertTrue(entry.toString().contains("alice"));
    }
}
