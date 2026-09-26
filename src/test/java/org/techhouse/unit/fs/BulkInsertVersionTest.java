package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class BulkInsertVersionTest {

    private FileSystem fileSystem;

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        fileSystem.createBaseDbPath();
        fileSystem.createAdminDatabase();
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() {
        final var dbDir = new File(TestGlobals.PATH);
        if (dbDir.exists() && dbDir.isDirectory() && dbDir.canRead() && dbDir.canWrite()
                && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            TestUtils.deleteFolder(dbDir);
        }
    }

    private static DbEntry entry(String id, long version) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("value", new JsonString("v"));
        final var dbEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        dbEntry.set_id(id);
        dbEntry.setPage(0);
        dbEntry.setVersion(version);
        return dbEntry;
    }

    private static IndexedDbEntry indexedById(List<IndexedDbEntry> entries, String id) {
        final var found = entries.stream().filter(e -> e.get_id().equals(id)).findFirst().orElse(null);
        assertNotNull(found, "the bulk insert must return an indexed entry for " + id);
        return found;
    }

    private PkIndexEntry pkById(String id) throws IOException {
        final var found = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(e -> e.getValue().equals(id)).findFirst().orElse(null);
        assertNotNull(found, "the bulk insert must write a pk index row for " + id);
        return found;
    }

    @Test
    public void test_the_returned_indexed_entry_carries_the_write_version() throws Exception {
        final var indexed = fileSystem.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL,
                List.of(entry("a", 41L), entry("b", 42L)));

        assertEquals(41L, indexedById(indexed, "a").getVersion(),
                "a dropped version publishes a version-0 document into the cache for every bulk insert");
        assertEquals(42L, indexedById(indexed, "b").getVersion());
    }

    @Test
    public void test_the_db_entry_the_indexed_entry_produces_carries_the_write_version() throws Exception {
        final var indexed = fileSystem.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL,
                List.of(entry("a", 41L), entry("b", 42L)));

        assertEquals(41L, indexedById(indexed, "a").toDbEntry().getVersion());
        assertEquals(42L, indexedById(indexed, "b").toDbEntry().getVersion());
    }

    @Test
    public void test_the_returned_version_matches_what_the_pk_index_recorded() throws Exception {
        final var indexed = fileSystem.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL,
                List.of(entry("a", 41L), entry("b", 42L)));

        assertEquals(pkById("a").getVersion(), indexedById(indexed, "a").getVersion());
        assertEquals(pkById("b").getVersion(), indexedById(indexed, "b").getVersion());
    }

    @Test
    public void test_a_zero_version_is_carried_through_unchanged() throws Exception {
        final var indexed = fileSystem.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL,
                List.of(entry("a", 0L)));

        assertEquals(0L, indexedById(indexed, "a").getVersion());
        assertEquals(0L, pkById("a").getVersion());
    }
}
