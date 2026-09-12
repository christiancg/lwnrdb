package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemReadTest {
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
    public void test_handle_folder_creation_failure() throws IOException {
        FileSystem fileSystem = new FileSystem();

        File mockFile = mock(File.class);
        File mockFolder = mock(File.class);

        when(mockFile.getParent()).thenReturn("/test/path");
        when(mockFolder.exists()).thenReturn(false);
        when(mockFolder.mkdir()).thenReturn(false);

        boolean result = fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        assertFalse(result);
        verify(mockFile, never()).createNewFile();
    }

    @Test
    public void test_get_by_id_throws_when_file_not_exists() {
        FileSystem fileSystem = new FileSystem();
        String dbName = "nonExistentDb";
        String collectionName = "nonExistentCollection";
        String id = "123";
        PkIndexEntry pkIndexEntry = new PkIndexEntry(dbName, collectionName, id, 0L, 100L, 0);

        assertThrows(FileNotFoundException.class, () -> fileSystem.getById(pkIndexEntry));
    }

    @Test
    public void test_get_by_id_returns_valid_db_entry() throws Exception {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        fileSystem.createBaseDbPath();
        fileSystem.createAdminDatabase();
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        String id = "123";

        JsonObject expectedJson = new JsonObject();
        expectedJson.addProperty("name", "test");
        expectedJson.addProperty(Globals.PK_FIELD, id);
        DbEntry dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.setData(expectedJson);
        dbEntry.set_id(id);
        PkIndexEntry indexEntry = fileSystem.insertIntoCollection(dbEntry);

        DbEntry result = fileSystem.getById(indexEntry);

        assertNotNull(result);
        assertEquals(TestGlobals.DB, result.getDatabaseName());
        assertEquals(TestGlobals.COLL, result.getCollectionName());
        assertEquals(id, result.get_id());
        assertEquals("test", result.getData().get("name").asJsonString().getValue());
    }

    @Test
    public void test_search_does_not_match_prefix_when_value_contains_pipe()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "testField";

        // Write two entries: "foo" and "foo|bar" — the search for "foo" must not hit "foo|bar"
        FieldIndexEntry<String> fooBar = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "foo|bar",
                Set.of("id2"));
        FieldIndexEntry<String> foo = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "foo", Set.of("id1"));
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, fooBar, null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, foo, null);

        FieldIndexEntry<String> fooEmpty = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "foo", Set.of());
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, null, fooEmpty);

        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-String.idx");
        String fileContent = Files.readString(indexFile.toPath());

        assertFalse(fileContent.contains("foo" + Globals.ID_SEPARATOR + "id1"),
                "exact 'foo' entry should have been removed");
        assertTrue(fileContent.contains("foo|bar" + Globals.ID_SEPARATOR + "id2"),
                "'foo|bar' entry must not be affected");
    }

    @Test
    public void test_returns_null_when_collection_folder_missing() throws NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "age";

        ConcurrentMap<String, List<FieldIndexEntry<?>>> result = fileSystem.readAllWholeFieldIndexFiles(TestGlobals.DB,
                "nonexistentCollection", fieldName);

        assertNull(result);
    }

    @Test
    public void test_stream_pages_yields_one_map_per_page()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        DbEntry e1 = new DbEntry();
        e1.set_id("1");
        e1.setDatabaseName(TestGlobals.DB);
        e1.setCollectionName(TestGlobals.COLL);
        e1.setData(new JsonObject());
        e1.setPage(0);
        fileSystem.insertIntoCollection(e1);

        DbEntry e2 = new DbEntry();
        e2.set_id("2");
        e2.setDatabaseName(TestGlobals.DB);
        e2.setCollectionName(TestGlobals.COLL);
        e2.setData(new JsonObject());
        e2.setPage(1);
        fileSystem.insertIntoCollection(e2);

        final var pages = fileSystem.streamPages(TestGlobals.DB, TestGlobals.COLL).toList();
        assertEquals(2, pages.size());
        final var allIds = new java.util.HashSet<String>();
        for (var p : pages)
            allIds.addAll(p.keySet());
        assertTrue(allIds.contains("1"));
        assertTrue(allIds.contains("2"));
    }

    @Test
    public void test_stream_pages_missing_folder_empty()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        assertEquals(0L, fileSystem.streamPages("noSuchDbNameForStreamTest", "noSuchCollName").count());
    }

    @Test
    public void test_pages_namespace_resolves_under_admin_pages_folder() throws Exception {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        fileSystem.createAdminDatabase();
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, "rdb", "coll");
        fileSystem.createCollectionFile(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);

        DbEntry entry = new DbEntry();
        entry.set_id("1");
        entry.setDatabaseName(Globals.ADMIN_PAGES_DB_NAME);
        entry.setCollectionName(pagesCollName);
        entry.setData(new JsonObject());
        entry.setPage(0);
        fileSystem.insertIntoCollection(entry);

        final var nestedFolder = new File(TestGlobals.PATH + File.separator + Globals.ADMIN_DB_NAME + File.separator
                + Globals.ADMIN_PAGES_FOLDER + File.separator + pagesCollName);
        assertTrue(nestedFolder.isDirectory(), "page collection must live under admin/pages/<db>_<coll>");
        assertTrue(new File(nestedFolder, pagesCollName + "-0" + Globals.DB_FILE_EXTENSION).exists());
        final var flatFolder = new File(
                TestGlobals.PATH + File.separator + Globals.ADMIN_DB_NAME + File.separator + pagesCollName);
        assertFalse(flatFolder.exists(), "no flat admin/<db>_<coll> folder should exist");
        assertEquals(1L, fileSystem.streamPages(Globals.ADMIN_PAGES_DB_NAME, pagesCollName)
                .flatMap(m -> m.keySet().stream()).filter("1"::equals).count());
    }

    @Test
    public void test_readWholeCollectionPage_skips_malformed_lines() throws Exception {
        final var fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);

        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("good");
        entry.setData(new JsonObject());
        entry.setPage(0L);
        fs.insertIntoCollection(entry);

        final var pageFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB
                + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL
                + Globals.FILE_PAGE_SEPARATOR + 0 + Globals.DB_FILE_EXTENSION);
        Files.writeString(pageFile.toPath(), "\nthis-is-not-json{partial", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        // The file is left untouched so the .idx byte offsets stay valid; rewriting the .dat here would
        // require a coordinated .idx rewrite - a real compaction.
        final var firstRead = fs.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 0L);
        assertEquals(1, firstRead.size());
        assertTrue(firstRead.containsKey("good"));

        final var diskLines = Files.readAllLines(pageFile.toPath());
        assertTrue(diskLines.stream().anyMatch(l -> l.contains("partial")),
                ".dat is intentionally left as-is to preserve .idx offsets");
    }

    @Test
    public void test_stream_entries_empty_collection()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        fs.createBaseDbPath();

        try (var stream = fs.streamEntries(TestGlobals.DB, "nonExistentColl")) {
            assertEquals(0, stream.count());
        }
    }

    @Test
    public void test_stream_entries_yields_all_across_pages()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        fs.createDatabaseFolder(TestGlobals.DB);
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        final var entries = new ArrayList<DbEntry>();
        JsonObject d1 = new JsonObject();
        d1.addProperty("f", "a");
        DbEntry e1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, d1);
        e1.set_id("a");
        e1.setPage(0);
        JsonObject d2 = new JsonObject();
        d2.addProperty("f", "b");
        DbEntry e2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, d2);
        e2.set_id("b");
        e2.setPage(1);
        entries.add(e1);
        entries.add(e2);
        fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, entries);

        final Set<String> ids;
        try (var stream = fs.streamEntries(TestGlobals.DB, TestGlobals.COLL)) {
            ids = stream.map(DbEntry::get_id).collect(java.util.stream.Collectors.toSet());
        }
        assertEquals(Set.of("a", "b"), ids);
    }

    @Test
    public void test_document_with_newline_occupies_one_line() throws Exception {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        fileSystem.createBaseDbPath();
        fileSystem.createAdminDatabase();
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        final var id = "nl1";
        final var value = "line1\nline2\twith \"quotes\" and \\slash";
        JsonObject data = new JsonObject();
        data.addProperty("text", value);
        data.addProperty(Globals.PK_FIELD, id);
        DbEntry entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.setData(data);
        entry.set_id(id);

        PkIndexEntry indexEntry = fileSystem.insertIntoCollection(entry);
        DbEntry read = fileSystem.getById(indexEntry);
        assertEquals(value, read.getData().get("text").asJsonString().getValue());

        File page = new File(TestGlobals.PATH + '/' + TestGlobals.DB + '/' + TestGlobals.COLL + '/' + TestGlobals.COLL
                + "-" + indexEntry.getPage() + Globals.DB_FILE_EXTENSION);
        assertEquals(1, java.nio.file.Files.readAllLines(page.toPath()).size());
    }
}
