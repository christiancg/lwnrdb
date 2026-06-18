package org.techhouse.unit.data.admin;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;

public class AdminPageEntryTest {

    @Test
    public void test_constructor_sets_correct_collection_name() {
        AdminPageEntry entry = new AdminPageEntry("myDb", "myColl");
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, "myDb", "myColl"),
                entry.getCollectionName());
    }

    @Test
    public void test_id_follows_deterministic_scheme() {
        AdminPageEntry entry = new AdminPageEntry("myDb", "myColl", 3L);
        assertEquals("myDb" + Globals.COLL_IDENTIFIER_SEPARATOR + "myColl" + Globals.COLL_IDENTIFIER_SEPARATOR + "3",
                entry.get_id());
        assertEquals(3L, entry.getPage());
    }

    @Test
    public void test_setters_sync_data() {
        AdminPageEntry entry = new AdminPageEntry("myDb", "myColl", 0L);
        entry.setEntryCount(7);
        entry.setPageSize(1024L);
        entry.setPage(2L);

        assertEquals(7, entry.getEntryCount());
        assertEquals(1024L, entry.getPageSize());
        assertEquals(2L, entry.getPage());

        JsonObject data = entry.getData();
        assertNotNull(data);
        assertEquals(7, data.get("entryCount").asJsonNumber().getValue().intValue());
        assertEquals(1024L, data.get("size").asJsonNumber().getValue().longValue());
        assertEquals(2L, data.get("page").asJsonNumber().getValue().longValue());
    }

    @Test
    public void test_from_json_object_populates_fields() {
        JsonObject obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, "myDb|myColl|5");
        obj.addProperty("page", 5L);
        obj.addProperty("entryCount", 12);
        obj.addProperty("size", 4096L);

        AdminPageEntry entry = AdminPageEntry.fromJsonObject("myDb", "myColl", obj);
        assertEquals("myDb|myColl|5", entry.get_id());
        assertEquals(5L, entry.getPage());
        assertEquals(12, entry.getEntryCount());
        assertEquals(4096L, entry.getPageSize());
    }

    @Test
    public void test_equals_same_instance() {
        AdminPageEntry entry = new AdminPageEntry("db", "coll", 0L);
        assertEquals(entry, entry);
    }

    @Test
    public void test_equals_symmetric() {
        AdminPageEntry entry1 = new AdminPageEntry("db", "coll", 1L);
        AdminPageEntry entry2 = new AdminPageEntry("db", "coll", 1L);
        assertEquals(entry1, entry2);
        assertEquals(entry2, entry1);
    }

    @Test
    public void test_equals_null_returns_false() {
        AdminPageEntry entry = new AdminPageEntry("db", "coll", 0L);
        assertNotEquals(null, entry);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        AdminPageEntry entry = new AdminPageEntry("db", "coll", 0L);
        assertNotEquals("notAnEntry", entry);
    }

    @Test
    public void test_equals_different_page_returns_false() {
        AdminPageEntry entry1 = new AdminPageEntry("db", "coll", 0L);
        AdminPageEntry entry2 = new AdminPageEntry("db", "coll", 1L);
        assertNotEquals(entry1, entry2);
    }

    @Test
    public void test_equals_different_entryCount_returns_false() {
        AdminPageEntry entry1 = new AdminPageEntry("db", "coll", 0L);
        AdminPageEntry entry2 = new AdminPageEntry("db", "coll", 0L);
        entry1.setEntryCount(5);
        entry2.setEntryCount(10);
        assertNotEquals(entry1, entry2);
    }

    @Test
    public void test_hashCode_same_values_equal() {
        AdminPageEntry entry1 = new AdminPageEntry("db", "coll", 2L);
        AdminPageEntry entry2 = new AdminPageEntry("db", "coll", 2L);
        assertEquals(entry1.hashCode(), entry2.hashCode());
    }

    @Test
    public void test_hashCode_different_page_differs() {
        AdminPageEntry entry1 = new AdminPageEntry("db", "coll", 0L);
        AdminPageEntry entry2 = new AdminPageEntry("db", "coll", 1L);
        assertNotEquals(entry1.hashCode(), entry2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        AdminPageEntry entry = new AdminPageEntry("db", "coll", 0L);
        assertNotNull(entry.toString());
    }
}
