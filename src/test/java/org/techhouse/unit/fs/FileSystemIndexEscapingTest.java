package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemIndexEscapingTest {
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

    @Test
    public void test_update_index_files_with_pipe_in_field_value()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "testField";

        FieldIndexEntry<String> entry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "foo|bar",
                Set.of("id1"));
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, entry, null);

        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-String.idx");
        String fileContent = Files.readString(indexFile.toPath());
        assertTrue(fileContent.contains("foo|bar" + Globals.ID_SEPARATOR + "id1"));
    }

    @Test
    public void test_field_index_round_trip_with_pipe_in_id()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "testField";

        FieldIndexEntry<String> entry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "someValue",
                Set.of("doc|id|one", "doc|id|two"));
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, entry, null);

        List<FieldIndexEntry<String>> index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL,
                fieldName, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals("someValue", index.getFirst().getValue());
        assertTrue(index.getFirst().getIds().contains("doc|id|one"));
        assertTrue(index.getFirst().getIds().contains("doc|id|two"));
    }

    @Test
    public void test_field_index_round_trip_with_semicolon_in_id()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "testField";

        FieldIndexEntry<String> entry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "someValue",
                Set.of("doc;id;one", "doc;id;two"));
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, entry, null);

        List<FieldIndexEntry<String>> index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL,
                fieldName, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals("someValue", index.getFirst().getValue());
        assertTrue(index.getFirst().getIds().contains("doc;id;one"));
        assertTrue(index.getFirst().getIds().contains("doc;id;two"));
    }

    @Test
    public void test_field_index_round_trip_with_pipe_and_semicolon_in_id()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "testField";

        Set<String> ids = Set.of("id|pipe", "id;semi", "id|and;both");
        FieldIndexEntry<String> entry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "someValue", ids);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, entry, null);

        List<FieldIndexEntry<String>> index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL,
                fieldName, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals(ids, index.getFirst().getIds());
    }

    @Test
    public void test_field_index_update_id_with_special_chars()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "testField";

        FieldIndexEntry<String> initial = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "aValue",
                Set.of("doc|one", "doc;two"));
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, initial, null);

        FieldIndexEntry<String> removed = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "aValue",
                Set.of("doc;two"));
        FieldIndexEntry<String> updated = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "aValue",
                Set.of("doc|one"));
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, updated, removed);

        List<FieldIndexEntry<String>> index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL,
                fieldName, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertTrue(index.getFirst().getIds().contains("doc|one"));
        assertFalse(index.getFirst().getIds().contains("doc;two"));
    }

    @Test
    public void test_pk_index_round_trip_with_pipe_in_id()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);

        DbEntry entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("my|custom|id");
        entry.setData(new JsonObject());
        entry.setPage(0L);

        PkIndexEntry saved = fileSystem.insertIntoCollection(entry);
        assertEquals("my|custom|id", saved.getValue());

        List<PkIndexEntry> index = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, index.size());
        assertEquals("my|custom|id", index.getFirst().getValue());
    }

    @Test
    public void test_pk_index_round_trip_with_semicolon_in_id()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);

        DbEntry entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("my;custom;id");
        entry.setData(new JsonObject());
        entry.setPage(0L);

        PkIndexEntry saved = fileSystem.insertIntoCollection(entry);
        assertEquals("my;custom;id", saved.getValue());

        List<PkIndexEntry> index = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, index.size());
        assertEquals("my;custom;id", index.getFirst().getValue());
    }

    @Test
    public void test_pk_index_round_trip_with_pipe_and_semicolon_in_id()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);

        DbEntry entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("my|and;special|id");
        entry.setData(new JsonObject());
        entry.setPage(0L);

        PkIndexEntry saved = fileSystem.insertIntoCollection(entry);
        assertEquals("my|and;special|id", saved.getValue());

        List<PkIndexEntry> index = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, index.size());
        assertEquals("my|and;special|id", index.getFirst().getValue());
    }

    @Test
    public void test_hash_index_write_and_read_round_trip()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "payload";

        FieldIndexEntry<String> objEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "aaaa1111",
                new HashSet<>(Set.of("id1", "id2")));
        fileSystem.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT, List.of(objEntry));
        FieldIndexEntry<String> arrEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "bbbb2222",
                new HashSet<>(Set.of("id3")));
        fileSystem.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.ARRAY, List.of(arrEntry));

        final var objIndex = fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName,
                IndexKind.OBJECT);
        assertNotNull(objIndex);
        assertEquals(1, objIndex.size());
        assertEquals("aaaa1111", objIndex.getFirst().getValue());
        assertEquals(Set.of("id1", "id2"), objIndex.getFirst().getIds());

        final var arrIndex = fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName,
                IndexKind.ARRAY);
        assertNotNull(arrIndex);
        assertEquals(1, arrIndex.size());
        assertEquals("bbbb2222", arrIndex.getFirst().getValue());
    }
}
