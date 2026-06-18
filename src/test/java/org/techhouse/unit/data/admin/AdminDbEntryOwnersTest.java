package org.techhouse.unit.data.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminDbEntry;

public class AdminDbEntryOwnersTest {
    @Test
    public void test_constructor_with_owners() {
        final var entry = new AdminDbEntry("mydb", new ArrayList<>(), List.of("Alice", "bob"));
        assertEquals(List.of("Alice", "bob"), entry.getOwners());
        assertTrue(entry.isOwner("Alice"));
        assertFalse(entry.isOwner("charlie"));
    }

    @Test
    public void test_set_owners_updates_data() {
        final var entry = new AdminDbEntry("mydb");
        entry.setOwners(List.of("Alice"));
        assertEquals(List.of("Alice"), entry.getOwners());
        assertTrue(entry.isOwner("Alice"));
    }

    @Test
    public void test_get_owners_default_empty() {
        final var entry = new AdminDbEntry("mydb");
        assertNotNull(entry.getOwners());
        assertTrue(entry.getOwners().isEmpty());
    }

    @Test
    public void test_from_json_round_trip_with_owners() {
        final var original = new AdminDbEntry("mydb", new ArrayList<>(), List.of("Alice", "bob"));
        final var restored = AdminDbEntry.fromJsonObject(original.getData());
        assertEquals(original.getOwners(), restored.getOwners());
        assertTrue(restored.isOwner("Alice"));
    }

    @Test
    public void test_from_json_backward_compat_no_owners_field() {
        // Simulate old entry without owners field
        final var original = new AdminDbEntry("old_db");
        original.getData().remove("owners");
        final var restored = AdminDbEntry.fromJsonObject(original.getData());
        assertNotNull(restored.getOwners());
        assertTrue(restored.getOwners().isEmpty());
    }

    @Test
    public void test_equals_and_hashcode_with_owners() {
        final var e1 = new AdminDbEntry("mydb", new ArrayList<>(), List.of("Alice"));
        final var e2 = new AdminDbEntry("mydb", new ArrayList<>(), List.of("Alice"));
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    public void test_toString_includes_owners() {
        final var entry = new AdminDbEntry("mydb", new ArrayList<>(), List.of("Alice"));
        assertTrue(entry.toString().contains("Alice"));
    }
}
