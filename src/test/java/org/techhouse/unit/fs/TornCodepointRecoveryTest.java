package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TornCodepointRecoveryTest {
    private static final String FIELD = "label";
    private static final String CJK = "日本語";

    @BeforeEach
    public void setUp() throws Exception {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        final var fileSystem = fs();
        fileSystem.createBaseDbPath();
        fileSystem.createAdminDatabase();
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() {
        final var dbDir = new File(TestGlobals.PATH);
        if (dbDir.exists() && dbDir.isDirectory() && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            TestUtils.deleteFolder(dbDir);
        }
    }

    private static FileSystem fs() throws NoSuchFieldException, IllegalAccessException {
        final var fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        return fileSystem;
    }

    private static File collectionFolder() {
        return new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);
    }

    private static File fileNamed(String suffix) {
        return new File(collectionFolder(), TestGlobals.COLL + suffix);
    }

    // Drops the final byte of the file, which lands inside the last multi-byte sequence and leaves a
    // codepoint torn exactly as a power loss mid-write would.
    private static void truncateOneByte(File file) throws IOException {
        final var bytes = Files.readAllBytes(file.toPath());
        Files.write(file.toPath(), java.util.Arrays.copyOf(bytes, bytes.length - 1));
    }

    private static void appendTornLine(File file, String prefix) throws IOException {
        final var torn = (prefix + CJK).getBytes(StandardCharsets.UTF_8);
        final var existing = Files.readAllBytes(file.toPath());
        final var combined = new byte[existing.length + torn.length - 1];
        System.arraycopy(existing, 0, combined, 0, existing.length);
        System.arraycopy(torn, 0, combined, existing.length, torn.length - 1);
        Files.write(file.toPath(), combined);
    }

    private static DbEntry entry(String id, String label) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add(FIELD, new JsonString(label));
        final var dbEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        dbEntry.set_id(id);
        return dbEntry;
    }

    @Test
    public void test_a_page_truncated_mid_codepoint_still_reads_its_other_lines() throws Exception {
        final var fileSystem = fs();
        fileSystem.insertIntoCollection(entry("p1", "plain"));
        fileSystem.insertIntoCollection(entry("p2", CJK));
        final var page = fileNamed("-0" + Globals.DB_FILE_EXTENSION);
        assertTrue(page.exists(), "the page file must exist before it is torn");
        appendTornLine(page, "{\"_id\":\"p3\",\"" + FIELD + "\":\"");

        final var read = fileSystem.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 0);

        assertNotNull(read);
        assertTrue(read.containsKey("p1"),
                "a torn trailing line must not hide the committed documents: " + read.keySet());
        assertTrue(read.containsKey("p2"), read.keySet().toString());
    }

    @Test
    public void test_a_strict_decode_of_a_torn_page_would_have_failed() throws Exception {
        final var fileSystem = fs();
        fileSystem.insertIntoCollection(entry("s1", CJK));
        final var page = fileNamed("-0" + Globals.DB_FILE_EXTENSION);
        appendTornLine(page, "{\"_id\":\"s2\",\"" + FIELD + "\":\"");

        assertThrows(MalformedInputException.class, () -> Files.readAllLines(page.toPath(), StandardCharsets.UTF_8),
                "if a strict decode stops throwing here the test no longer proves anything");
    }

    @Test
    public void test_a_field_index_truncated_mid_codepoint_self_heals() throws Exception {
        final var fileSystem = fs();
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "plain", Set.of("id1")), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, CJK, Set.of("id2")), null);
        truncateOneByte(fileNamed("-" + FIELD + "-String.idx"));

        final List<FieldIndexEntry<String>> index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB,
                TestGlobals.COLL, FIELD, String.class);

        assertNotNull(index, "a torn codepoint must not make the whole index unreadable");
        assertTrue(index.stream().anyMatch(e -> e.getValue().equals("plain")),
                "the intact entry must survive: " + index);
    }

    @Test
    public void test_a_pk_index_truncated_mid_codepoint_self_heals() throws Exception {
        final var fileSystem = fs();
        fileSystem.insertIntoCollection(entry("k1", "plain"));
        fileSystem.insertIntoCollection(entry("k2", CJK));
        final var pkIndex = fileNamed("-_id-String.idx");
        appendTornLine(pkIndex, "torn" + Globals.ID_SEPARATOR + "0" + Globals.ID_SEPARATOR + "1" + Globals.ID_SEPARATOR
                + "0" + Globals.ID_SEPARATOR);

        final var read = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(read);
        assertEquals(2, read.stream().filter(e -> e.getValue().equals("k1") || e.getValue().equals("k2")).count(),
                "both intact pk entries must survive a torn trailing line: " + read);
    }
}
