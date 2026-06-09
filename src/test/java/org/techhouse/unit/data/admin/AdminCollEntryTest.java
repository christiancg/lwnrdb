package org.techhouse.unit.data.admin;

import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class AdminCollEntryTest {
    private static final String INDEXES_FIELD_NAME = "indexes";

    @Test
    public void test_default_constructor_sets_admin_names() {
        AdminCollEntry entry = new AdminCollEntry("db", "coll");
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, entry.getCollectionName());
        assertEquals(Set.of(), entry.getIndexes());
        assertTrue(entry.get_id().contains("db"));
        assertTrue(entry.get_id().contains("coll"));
    }

    @Test
    public void test_constructs_admin_coll_entry_with_indexes() {
        String dbName = "testDb";
        String collName = "testCollection";
        Set<String> indexes = new HashSet<>();
        indexes.add("index1");
        indexes.add("index2");

        AdminCollEntry entry = new AdminCollEntry(dbName, collName, indexes);

        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, entry.getCollectionName());
        assertEquals(Cache.getCollectionIdentifier(dbName, collName), entry.get_id());
        assertEquals(indexes, entry.getIndexes());
    }

    @Test
    public void test_handles_empty_indexes_set() {
        String dbName = "testDb";
        String collName = "testCollection";
        Set<String> indexes = new HashSet<>();

        AdminCollEntry entry = new AdminCollEntry(dbName, collName, indexes);

        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, entry.getCollectionName());
        assertEquals(Cache.getCollectionIdentifier(dbName, collName), entry.get_id());
        assertTrue(entry.getIndexes().isEmpty());
    }

    @Test
    public void test_from_json_object_valid_input() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(Globals.PK_FIELD, "test_id");
        JsonArray indexesArray = new JsonArray();
        indexesArray.add("index1");
        indexesArray.add("index2");
        jsonObject.add(INDEXES_FIELD_NAME, indexesArray);

        AdminCollEntry entry = AdminCollEntry.fromJsonObject(jsonObject);

        assertEquals("test_id", entry.get_id());
        assertEquals(Set.of("index1", "index2"), entry.getIndexes());
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, entry.getCollectionName());
    }

    @Test
    public void test_update_indexes_with_new_set() {
        AdminCollEntry entry = new AdminCollEntry("testDb", "testCollection");
        Set<String> newIndexes = new HashSet<>(Arrays.asList("index1", "index2"));
        entry.setIndexes(newIndexes);

        JsonObject data = entry.getData();
        JsonArray indexesArray = data.get("indexes").asJsonArray();

        assertEquals(2, indexesArray.size());
        assertTrue(indexesArray.contains(new JsonString("index1")));
        assertTrue(indexesArray.contains(new JsonString("index2")));
    }
}
