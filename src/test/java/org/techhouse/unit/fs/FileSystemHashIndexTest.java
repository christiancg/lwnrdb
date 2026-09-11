package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemHashIndexTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        fileSystem.createBaseDbPath();
        fileSystem.createAdminDatabase();
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() {
        File dbDir = new File(TestGlobals.PATH);
        if (dbDir.exists() && dbDir.isDirectory() && dbDir.canRead() && dbDir.canWrite()
                && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            TestUtils.deleteFolder(dbDir);
        }
    }

    // writeHashIndexFile with an empty list writes nothing; reading returns null
    @Test
    public void test_hash_index_write_empty_list_is_noop()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        fileSystem.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, "payload", IndexKind.OBJECT, List.of());
        assertNull(fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, "payload", IndexKind.OBJECT));
    }

    // updateHashIndexFiles inserts a new entry then removes it again
    @Test
    public void test_hash_index_update_insert_then_remove()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "payload";

        FieldIndexEntry<String> inserted = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "cccc3333",
                new HashSet<>(Set.of("id1")));
        fileSystem.updateHashIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT, inserted, null);
        var index = fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT);
        assertNotNull(index);
        assertEquals(1, index.size());

        FieldIndexEntry<String> removed = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "cccc3333",
                new HashSet<>());
        fileSystem.updateHashIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT, null, removed);
        index = fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT);
        assertTrue(index == null || index.stream().noneMatch(e -> e.getValue().equals("cccc3333")));
    }

    // dropIndex removes the per-kind hash index files alongside scalar ones
    @Test
    public void test_drop_index_removes_hash_index_files()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "payload";
        fileSystem.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT, List
                .of(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "dddd4444", new HashSet<>(Set.of("a")))));
        fileSystem.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.ARRAY, List
                .of(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "eeee5555", new HashSet<>(Set.of("b")))));

        assertTrue(fileSystem.dropIndex(TestGlobals.DB, TestGlobals.COLL, fieldName));
        assertNull(fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT));
        assertNull(fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.ARRAY));
    }
}
