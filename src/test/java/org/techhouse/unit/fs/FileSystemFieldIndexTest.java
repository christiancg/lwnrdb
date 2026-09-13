package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemFieldIndexTest {
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
    public void test_getById_data_contains_id_field() throws Exception {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        fileSystem.createBaseDbPath();
        fileSystem.createAdminDatabase();
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        String id = "abc123";
        JsonObject data = new JsonObject();
        data.addProperty("value", "hello");
        data.addProperty(Globals.PK_FIELD, id);
        DbEntry dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.setData(data);
        dbEntry.set_id(id);
        PkIndexEntry indexEntry = fileSystem.insertIntoCollection(dbEntry);

        DbEntry result = fileSystem.getById(indexEntry);

        assertNotNull(result);
        assertTrue(result.getData().has(Globals.PK_FIELD), "_id must be present in getData() for positioned reads");
        assertEquals(id, result.getData().get(Globals.PK_FIELD).asJsonString().getValue());
        assertEquals(id, result.get_id());
    }

    @Test
    public void test_insert_entry_creates_index() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);

        JsonObject data = new JsonObject();
        data.addProperty("name", "test");

        DbEntry entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);

        PkIndexEntry result = fs.insertIntoCollection(entry);

        assertNotNull(result);
        assertEquals(entry.get_id(), result.getValue());
        assertEquals(TestGlobals.DB, result.getDatabaseName());
        assertEquals(TestGlobals.COLL, result.getCollectionName());
        assertTrue(result.getPosition() >= 0);
        assertTrue(result.getLength() > 0);

        final var filePath = TestUtils.getDbPath(fs);
        File collFile = new File(filePath + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + "0.dat");
        assertTrue(collFile.exists());
    }

    @Test
    public void test_writes_index_entries_to_file() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "testField";
        Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap = new HashMap<>();
        List<FieldIndexEntry<?>> stringEntries = List.of(
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value1", Set.of("id1", "id2")),
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value2", Set.of("id3")));
        indexEntryMap.put(String.class, stringEntries);
        fileSystem.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, indexEntryMap);
        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-String.idx");
        String fileContent = Files.readString(indexFile.toPath());
        assertTrue(fileContent.contains("value1" + Globals.ID_SEPARATOR + "id2" + Globals.ID_SEPARATOR + "id1")
                || fileContent.contains("value1" + Globals.ID_SEPARATOR + "id1" + Globals.ID_SEPARATOR + "id2"));
        assertTrue(fileContent.contains("value2" + Globals.ID_SEPARATOR + "id3"));
        assertTrue(fileContent.endsWith(Globals.NEWLINE));
    }

    @Test
    public void test_update_index_files_with_insert_and_remove()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "testField";

        Set<String> ids1 = new HashSet<>(Arrays.asList("id1", "id2"));
        Set<String> ids2 = new HashSet<>(Arrays.asList("id3", "id4"));

        FieldIndexEntry<String> insertEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value1", ids1);
        FieldIndexEntry<String> removeEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value2", ids2);

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, insertEntry, removeEntry);
        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-String.idx");
        String fileContent = Files.readString(indexFile.toPath());

        assertTrue(fileContent.contains("value1" + Globals.ID_SEPARATOR + "id1" + Globals.ID_SEPARATOR + "id2")
                || fileContent.contains("value1" + Globals.ID_SEPARATOR + "id2" + Globals.ID_SEPARATOR + "id1"));
        assertFalse(fileContent.contains("value2" + Globals.ID_SEPARATOR + "id3" + Globals.ID_SEPARATOR + "id4")
                || fileContent.contains("value2" + Globals.ID_SEPARATOR + "id4" + Globals.ID_SEPARATOR + "id3"));
    }

    @Test
    public void test_update_index_files_with_empty_ids_remove()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);

        String fieldName = "testField";

        Set<String> emptyIds = new HashSet<>();
        FieldIndexEntry<Integer> removeEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 123, emptyIds);

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, null, removeEntry);

        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-Number.idx");
        String fileContent = Files.readString(indexFile.toPath());

        assertFalse(fileContent.contains("123" + Globals.INDEX_ENTRY_SEPARATOR));
    }

    @Test
    public void test_delete_index_files_success() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);

        String fieldName = "testField";

        File mockCollFolder = new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);

        File indexFile1 = new File(mockCollFolder, "1" + Globals.INDEX_FILE_NAME_SEPARATOR + fieldName
                + Globals.INDEX_FILE_NAME_SEPARATOR + "idx" + Globals.INDEX_FILE_EXTENSION);
        File indexFile2 = new File(mockCollFolder, "2" + Globals.INDEX_FILE_NAME_SEPARATOR + fieldName
                + Globals.INDEX_FILE_NAME_SEPARATOR + "idx" + Globals.INDEX_FILE_EXTENSION);

        assertTrue(indexFile1.createNewFile());
        assertTrue(indexFile2.createNewFile());

        boolean result = fs.dropIndex(TestGlobals.DB, TestGlobals.COLL, fieldName);

        assertTrue(result);
        assertFalse(indexFile1.exists());
        assertFalse(indexFile2.exists());
    }

    @Test
    public void test_read_number_type_index_entries() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);

        String fieldName = "testField";
        Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap = new HashMap<>();
        List<FieldIndexEntry<?>> stringEntries = List.of(
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value1", Set.of("id1", "id2")),
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value2", Set.of("id3")));
        indexEntryMap.put(String.class, stringEntries);
        fs.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, indexEntryMap);

        List<FieldIndexEntry<String>> entries = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName,
                String.class);

        assertNotNull(entries);
        assertEquals(2, entries.size());
        assertTrue(entries.getFirst().getIds().contains("id1") && entries.getFirst().getIds().contains("id2"));
        assertTrue(entries.get(1).getIds().contains("id3"));
    }

    @Test
    public void test_read_index_file_returns_sorted_entries()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);

        File mockIndexFile = mock(File.class);
        when(mockIndexFile.exists()).thenReturn(true);
        Path path = Path.of(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-_id-String.idx");
        when(mockIndexFile.toPath()).thenReturn(path);

        List<String> fileLines = Arrays.asList(
                "value3" + Globals.INDEX_ENTRY_SEPARATOR + "300" + Globals.INDEX_ENTRY_SEPARATOR + "100"
                        + Globals.INDEX_ENTRY_SEPARATOR + "0" + Globals.INDEX_ENTRY_SEPARATOR + "0",
                "value1" + Globals.INDEX_ENTRY_SEPARATOR + "100" + Globals.INDEX_ENTRY_SEPARATOR + "100"
                        + Globals.INDEX_ENTRY_SEPARATOR + "0" + Globals.INDEX_ENTRY_SEPARATOR + "0",
                "value2" + Globals.INDEX_ENTRY_SEPARATOR + "200" + Globals.INDEX_ENTRY_SEPARATOR + "100"
                        + Globals.INDEX_ENTRY_SEPARATOR + "0" + Globals.INDEX_ENTRY_SEPARATOR + "0");
        Files.write(path, fileLines);

        List<PkIndexEntry> result = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(3, result.size());
        assertEquals("value1", result.get(0).getValue());
        assertEquals("value2", result.get(1).getValue());
        assertEquals("value3", result.get(2).getValue());
    }

    @Test
    public void test_get_by_index_entries_empty_input()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);

        assertTrue(fs.getByIndexEntries(null).isEmpty());
        assertTrue(fs.getByIndexEntries(new ArrayList<>()).isEmpty());
    }

    @Test
    public void test_get_by_index_entries_reads_requested_across_pages()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        fs.createDatabaseFolder(TestGlobals.DB);
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        final var entries = new ArrayList<DbEntry>();
        for (int i = 0; i < 3; i++) {
            JsonObject d = new JsonObject();
            d.addProperty("v", i);
            DbEntry e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, d);
            e.set_id("p0-" + i);
            e.setPage(0);
            entries.add(e);
        }
        JsonObject d3 = new JsonObject();
        d3.addProperty("v", 99);
        DbEntry e3 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, d3);
        e3.set_id("p1-0");
        e3.setPage(1);
        entries.add(e3);

        final var indexed = fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, entries);
        final var byId = new HashMap<String, PkIndexEntry>();
        for (var ix : indexed) {
            byId.put(ix.get_id(), ix.getIndex());
        }

        final var requested = List.of(byId.get("p0-1"), byId.get("p1-0"));
        final var result = fs.getByIndexEntries(requested);

        assertEquals(2, result.size());
        final var ids = result.stream().map(DbEntry::get_id).collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.contains("p0-1"));
        assertTrue(ids.contains("p1-0"));
        final var p01 = result.stream().filter(e -> e.get_id().equals("p0-1")).findFirst().orElseThrow();
        assertEquals(1, p01.getData().get("v").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_get_by_index_entries_single_page_multiple()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        fs.createDatabaseFolder(TestGlobals.DB);
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        final var entries = new ArrayList<DbEntry>();
        for (int i = 0; i < 3; i++) {
            JsonObject d = new JsonObject();
            d.addProperty("name", "n" + i);
            DbEntry e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, d);
            e.set_id("id-" + i);
            entries.add(e);
        }
        final var indexed = fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, entries);
        final var pkEntries = indexed.stream().map(IndexedDbEntry::getIndex)
                .collect(java.util.stream.Collectors.toList());

        final var result = fs.getByIndexEntries(pkEntries);
        assertEquals(3, result.size());
        final var ids = result.stream().map(DbEntry::get_id).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("id-0", "id-1", "id-2"), ids);
    }

    @Test
    public void test_update_returns_index_entry_and_compaction()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var entry1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry1.set_id("1");
        final var entry2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry2.set_id("2");
        final var pk1 = fileSystem.insertIntoCollection(entry1);
        fileSystem.insertIntoCollection(entry2);
        data.get("name").asJsonString().setValue("updated");
        final var updatedEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        updatedEntry.set_id("1");

        // Updating entry "1" (not the last one) relocates it to the end, so a survivor moves.
        final var result = fileSystem.updateFromCollection(updatedEntry, pk1);

        assertNotNull(result.indexEntry());
        assertEquals("1", result.indexEntry().getValue());
        assertNotNull(result.compaction());
        assertEquals(pk1.getPosition(), result.compaction().removedPosition());
        assertEquals(pk1.getLength(), result.compaction().removedLength());
    }
}
