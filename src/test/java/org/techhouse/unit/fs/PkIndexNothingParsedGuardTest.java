package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PkIndexNothingParsedGuardTest {
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

    private static File pkIndexFile() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.PK_INDEX_FILE_NAME + Globals.INDEX_FILE_EXTENSION);
    }

    private static DbEntry document(String id) {
        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id(id);
        entry.setData(new JsonObject());
        entry.setPage(0L);
        return entry;
    }

    @Test
    public void test_update_after_pk_index_corruption_refuses_to_rewrite()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        final var fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);

        final var good1Index = fs.insertIntoCollection(document("good1"));
        fs.insertIntoCollection(document("good2"));

        Files.writeString(pkIndexFile().toPath(), "this is not a valid pk index line\nnor is this",
                StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        final var corruptedBytes = Files.readAllBytes(pkIndexFile().toPath());

        assertThrows(RuntimeException.class, () -> fs.deleteFromCollection(good1Index));

        final var afterBytes = Files.readAllBytes(pkIndexFile().toPath());
        assertArrayEquals(corruptedBytes, afterBytes,
                "a pk index write that could not parse a single line must leave the file untouched");
    }

    @Test
    public void test_update_with_one_malformed_and_one_valid_line_still_self_heals()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        final var fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);

        fs.insertIntoCollection(document("good1"));
        Files.writeString(pkIndexFile().toPath(), "\nthis is not a valid pk index line\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        final var good2Index = fs.insertIntoCollection(document("good2"));

        fs.deleteFromCollection(good2Index);

        final var diskLines = Files.readAllLines(pkIndexFile().toPath());
        assertTrue(diskLines.stream().noneMatch(l -> l.contains("not a valid")),
                "the malformed pk index line should have been removed");
        assertEquals(1, diskLines.stream().filter(l -> !l.isEmpty()).count());

        final var remaining = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, remaining.size());
        assertEquals("good1", remaining.getFirst().getValue());
    }

    @Test
    public void test_reading_an_old_grammar_pk_index_fails_and_leaves_it_intact()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        final var fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        fs.insertIntoCollection(document("good1"));
        Files.writeString(pkIndexFile().toPath(), "a|0|20|0|0\nb|20|20|0|0\n", StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        final var planted = Files.readAllBytes(pkIndexFile().toPath());

        final var failure = assertThrows(IOException.class,
                () -> fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL));

        assertTrue(failure.getMessage().contains(pkIndexFile().getName()));
        assertArrayEquals(planted, Files.readAllBytes(pkIndexFile().toPath()));
    }
}
