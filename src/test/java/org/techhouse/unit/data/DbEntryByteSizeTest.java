package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AccessKind;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminCollectionUsageEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;

public class DbEntryByteSizeTest {
    private static DbEntry entryWith(String value) {
        final var data = new JsonObject();
        data.addProperty("field", value);
        final var entry = new DbEntry();
        entry.setDatabaseName("db");
        entry.setCollectionName("coll");
        entry.set_id("id1");
        entry.setData(data);
        return entry;
    }

    private static int actualBytes(DbEntry entry) {
        return (entry.toFileEntry() + Globals.NEWLINE).getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    public void test_byte_size_matches_the_serialized_length() {
        final var entry = entryWith("hello");
        assertEquals(actualBytes(entry), entry.byteSize());
    }

    @Test
    public void test_repeated_calls_return_the_same_value() {
        final var entry = entryWith("hello");
        final var first = entry.byteSize();
        assertEquals(first, entry.byteSize());
        assertEquals(first, entry.byteSize());
    }

    @Test
    public void test_set_data_invalidates_the_cached_size() {
        final var entry = entryWith("short");
        final var before = entry.byteSize();

        final var bigger = new JsonObject();
        bigger.addProperty("field", "a considerably longer value than before");
        entry.setData(bigger);

        final var after = entry.byteSize();
        assertNotEquals(before, after);
        assertEquals(actualBytes(entry), after);
    }

    @Test
    public void test_set_id_invalidates_the_cached_size() {
        final var entry = entryWith("hello");
        final var before = entry.byteSize();

        entry.set_id("a-much-longer-identifier");

        final var after = entry.byteSize();
        assertNotEquals(before, after);
        assertEquals(actualBytes(entry), after);
    }

    @Test
    public void test_null_data_is_zero_bytes() {
        final var entry = new DbEntry();
        assertEquals(0, entry.byteSize());
    }

    @Test
    public void test_admin_page_entry_size_tracks_in_place_updates() {
        final var entry = new AdminPageEntry("db", "coll", 0);
        entry.setPageSize(1);
        final var before = entry.byteSize();

        entry.setPageSize(123456789L);

        assertNotEquals(before, entry.byteSize());
        assertEquals(actualBytes(entry), entry.byteSize());
    }

    @Test
    public void test_admin_usage_entry_size_tracks_in_place_updates() {
        final var entry = new AdminCollectionUsageEntry(AccessKind.COLLECTION, "db", "coll", null, 1, 1);
        final var before = entry.byteSize();

        entry.setAccessCount(9999999999L);

        assertNotEquals(before, entry.byteSize());
        assertEquals(actualBytes(entry), entry.byteSize());
    }
}
