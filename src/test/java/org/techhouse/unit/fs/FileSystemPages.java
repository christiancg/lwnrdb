package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

// The page-level fixture the PK-index and write suites both drive: a FileSystem rooted at the test
// path, an entry placed on a chosen page, and a read back through the index rather than the cache.
final class FileSystemPages {

    private FileSystemPages() {
    }

    static FileSystem freshFs() throws NoSuchFieldException, IllegalAccessException, IOException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        fs.createDatabaseFolder(TestGlobals.DB);
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
        return fs;
    }

    static PkIndexEntry insertOnPage(FileSystem fs, String id, long page) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty(Globals.PK_FIELD, id);
        data.addProperty("v", "short");
        DbEntry e = new DbEntry();
        e.setDatabaseName(TestGlobals.DB);
        e.setCollectionName(TestGlobals.COLL);
        e.set_id(id);
        e.setData(data);
        e.setPage(page);
        return fs.insertIntoCollection(e);
    }

    static IndexedDbEntry updateEntry(PkIndexEntry idx, String id, String newValue) {
        JsonObject data = new JsonObject();
        data.addProperty(Globals.PK_FIELD, id);
        data.addProperty("v", newValue);
        IndexedDbEntry u = new IndexedDbEntry();
        u.setIndex(idx);
        u.setDatabaseName(TestGlobals.DB);
        u.setCollectionName(TestGlobals.COLL);
        u.set_id(id);
        u.setData(data);
        return u;
    }

    static String readValueFromDisk(FileSystem fs, String id) throws Exception {
        final var disk = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        final var pk = disk.stream().filter(p -> p.getValue().equals(id)).findFirst().orElseThrow();
        assertTrue(pk.getPosition() >= 0, "PK index position for '" + id + "' must not be negative");
        return fs.getById(pk).getData().get("v").asJsonString().getValue();
    }
}
