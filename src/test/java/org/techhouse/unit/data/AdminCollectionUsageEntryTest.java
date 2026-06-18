package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.cache.AccessKind;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollectionUsageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class AdminCollectionUsageEntryTest {

    @Test
    public void test_construct_with_all_fields() {
        final var entry = new AdminCollectionUsageEntry(AccessKind.COLLECTION, "myDb", "myColl", "myField", 42L,
                1_000L);
        assertEquals(AccessKind.COLLECTION, entry.getKind());
        assertEquals("myDb", entry.getDbName());
        assertEquals("myColl", entry.getCollName());
        assertEquals("myField", entry.getIndexKey());
        assertEquals(42L, entry.getAccessCount());
        assertEquals(1_000L, entry.getLastAccessMillis());
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_COLLECTION_USAGE_NAME, entry.getCollectionName());
        assertEquals(AdminCollectionUsageEntry.buildId("myDb", "myColl", "myField"), entry.get_id());
    }

    @Test
    public void test_null_indexKey_becomes_empty() {
        final var entry = new AdminCollectionUsageEntry(AccessKind.PK_INDEX, "db", "coll", null, 1L, 1L);
        assertEquals("", entry.getIndexKey());
    }

    @Test
    public void test_buildId_format() {
        assertEquals("db" + Globals.COLL_IDENTIFIER_SEPARATOR + "coll" + Globals.COLL_IDENTIFIER_SEPARATOR + "field",
                AdminCollectionUsageEntry.buildId("db", "coll", "field"));
        assertEquals("db" + Globals.COLL_IDENTIFIER_SEPARATOR + "coll" + Globals.COLL_IDENTIFIER_SEPARATOR,
                AdminCollectionUsageEntry.buildId("db", "coll", null));
    }

    @Test
    public void test_fromJsonObject_round_trip() {
        final var data = new JsonObject();
        final var id = AdminCollectionUsageEntry.buildId("db", "coll", "field");
        data.add(Globals.PK_FIELD, new JsonString(id));
        data.add("kind", new JsonString(AccessKind.FIELD_INDEX.name()));
        data.add("dbName", new JsonString("db"));
        data.add("collName", new JsonString("coll"));
        data.add("indexKey", new JsonString("field"));
        data.add("accessCount", new JsonString("7"));
        data.add("lastAccessMillis", new JsonString("123"));
        final var entry = AdminCollectionUsageEntry.fromJsonObject(data);
        assertEquals(AccessKind.FIELD_INDEX, entry.getKind());
        assertEquals("db", entry.getDbName());
        assertEquals("coll", entry.getCollName());
        assertEquals("field", entry.getIndexKey());
        assertEquals(7L, entry.getAccessCount());
        assertEquals(123L, entry.getLastAccessMillis());
        assertEquals(id, entry.get_id());
    }

    @Test
    public void test_setters_update_data() {
        final var entry = new AdminCollectionUsageEntry(AccessKind.COLLECTION, "db", "coll", "", 1L, 2L);
        entry.setAccessCount(99L);
        entry.setLastAccessMillis(500L);
        assertEquals(99L, entry.getAccessCount());
        assertEquals(500L, entry.getLastAccessMillis());
        assertEquals("99", entry.getData().get("accessCount").asJsonString().getValue());
        assertEquals("500", entry.getData().get("lastAccessMillis").asJsonString().getValue());
    }

    @Test
    public void test_equals_and_hashCode() {
        final var e1 = new AdminCollectionUsageEntry(AccessKind.COLLECTION, "db", "coll", "", 1L, 2L);
        final var e2 = new AdminCollectionUsageEntry(AccessKind.COLLECTION, "db", "coll", "", 1L, 2L);
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    public void test_equals_same_instance() {
        final var e = new AdminCollectionUsageEntry(AccessKind.COLLECTION, "db", "coll", "", 1L, 2L);
        assertEquals(e, e);
    }

    @Test
    public void test_equals_null_and_different_class() {
        final var e = new AdminCollectionUsageEntry(AccessKind.COLLECTION, "db", "coll", "", 1L, 2L);
        assertNotEquals(null, e);
        assertNotEquals("string", e);
    }

    @Test
    public void test_equals_different_kind() {
        final var e1 = new AdminCollectionUsageEntry(AccessKind.COLLECTION, "db", "coll", "", 1L, 2L);
        final var e2 = new AdminCollectionUsageEntry(AccessKind.PK_INDEX, "db", "coll", "", 1L, 2L);
        assertNotEquals(e1, e2);
    }

    @Test
    public void test_toString_not_null() {
        final var e = new AdminCollectionUsageEntry(AccessKind.COLLECTION, "db", "coll", "", 1L, 2L);
        assertNotNull(e.toString());
    }
}
