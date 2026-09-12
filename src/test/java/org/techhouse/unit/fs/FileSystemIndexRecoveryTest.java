package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.techhouse.data.IndexKind;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemIndexRecoveryTest {
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
    public void test_empty_index_entry_map() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        String fieldName = "testField";
        Map<Class<?>, List<FieldIndexEntry<?>>> emptyMap = new HashMap<>();
        fileSystem.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, emptyMap);
        final var index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName,
                String.class);
        assertNull(index);
    }

    @Test
    public void test_drop_index_nonexistent_collection() throws NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);

        String dbName = "nonExistentDb";
        String collName = "nonExistentColl";
        String fieldName = "testField";

        boolean result = fs.dropIndex(dbName, collName, fieldName);

        assertFalse(result);
    }

    @Test
    public void test_empty_index_file_returns_empty_list()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);

        List<FieldIndexEntry<String>> entries = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL,
                "emptyField", String.class);

        assertNull(entries);
    }

    @Test
    public void test_empty_index_file_returns_empty_list_2()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);

        File mockIndexFile = mock(File.class);
        when(mockIndexFile.exists()).thenReturn(false);

        List<PkIndexEntry> result = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(result.isEmpty());
    }

    @Test
    public void test_readWholePkIndexFile_drops_and_rewrites_malformed_lines() throws Exception {
        final var fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);

        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("good");
        entry.setData(new JsonObject());
        entry.setPage(0L);
        fs.insertIntoCollection(entry);

        final var indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB
                + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL
                + Globals.INDEX_FILE_NAME_SEPARATOR + Globals.PK_FIELD + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.INDEX_TYPE_STRING + Globals.INDEX_FILE_EXTENSION);
        Files.writeString(indexFile.toPath(), "\nthis is not a valid pk index line", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        final var firstRead = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, firstRead.size());
        assertEquals("good", firstRead.getFirst().getValue());

        final var diskLines = Files.readAllLines(indexFile.toPath());
        assertTrue(diskLines.stream().noneMatch(l -> l.contains("not a valid")),
                "the malformed PK index line should have been removed");

        final var secondRead = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, secondRead.size());
    }

    @Test
    public void test_readWholePkIndexFile_dedups_duplicate_keys_keeping_last() throws Exception {
        final var fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);

        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("dup");
        entry.setData(new JsonObject());
        entry.setPage(0L);
        fs.insertIntoCollection(entry);

        final var indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB
                + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL
                + Globals.INDEX_FILE_NAME_SEPARATOR + Globals.PK_FIELD + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.INDEX_TYPE_STRING + Globals.INDEX_FILE_EXTENSION);
        Files.writeString(indexFile.toPath(), "\ndup|172|172|0|0", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        final var firstRead = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, firstRead.size(), "duplicate id must collapse to a single entry");
        assertEquals("dup", firstRead.getFirst().getValue());
        assertEquals(172L, firstRead.getFirst().getPosition(), "the last occurrence (freshest) must win");

        final var diskLines = Files.readAllLines(indexFile.toPath());
        assertEquals(1, diskLines.stream().filter(l -> !l.isEmpty()).count(),
                "the duplicate line should have been rewritten away");

        final var secondRead = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, secondRead.size());
    }

    @Test
    public void test_readWholeFieldIndexFiles_drops_and_rewrites_malformed_lines() throws Exception {
        final var fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        final var fieldName = "selfHeal";

        final var entry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "good", Set.of("id1"));
        fs.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, entry, null);

        final var indexFile = findIdxFile(fieldName);
        Files.writeString(indexFile.toPath(), "\nthis is not a valid field index line", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        final var firstRead = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, String.class);
        assertEquals(1, firstRead.size());
        assertEquals("good", firstRead.getFirst().getValue());

        final var diskLines = Files.readAllLines(indexFile.toPath());
        assertTrue(diskLines.stream().noneMatch(l -> l.contains("not a valid")),
                "the malformed field index line should have been removed");

        final var secondRead = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, String.class);
        assertEquals(1, secondRead.size());
    }

    @Test
    public void test_readWholeHashIndexFile_drops_and_rewrites_malformed_lines() throws Exception {
        final var fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        final var fieldName = "hashSelfHeal";

        final var entry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "aaaa1111",
                new HashSet<>(Set.of("id1")));
        fs.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT, List.of(entry));

        final var indexFile = findIdxFile(fieldName);
        Files.writeString(indexFile.toPath(), "\nthis is not a valid hash index line", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        final var firstRead = fs.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT);
        assertEquals(1, firstRead.size());
        assertEquals("aaaa1111", firstRead.getFirst().getValue());

        final var diskLines = Files.readAllLines(indexFile.toPath());
        assertTrue(diskLines.stream().noneMatch(l -> l.contains("not a valid")),
                "the malformed hash index line should have been removed");

        final var secondRead = fs.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT);
        assertEquals(1, secondRead.size());
    }

    private static File findIdxFile(String fieldName) throws IOException {
        final var collFolder = Path.of(TestGlobals.PATH, TestGlobals.DB, TestGlobals.COLL);
        try (var files = Files.list(collFolder)) {
            return files.map(Path::toFile)
                    .filter(f -> f.getName().contains(Globals.INDEX_FILE_NAME_SEPARATOR + fieldName)
                            && f.getName().endsWith(Globals.INDEX_FILE_EXTENSION))
                    .findFirst().orElseThrow();
        }
    }
}
