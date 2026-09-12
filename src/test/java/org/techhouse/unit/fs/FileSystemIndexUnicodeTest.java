package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemIndexUnicodeTest {
    private static final String FIELD = "testField";
    private static final String ACCENTED = "café";
    private static final String CJK = "日本語";
    private static final String EMOJI = "a😀b";

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

    private static FileSystem fs() throws NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        return fileSystem;
    }

    private static FieldIndexEntry<String> entry(String value, String... ids) {
        return new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, value, Set.of(ids));
    }

    private static File indexFile() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + FIELD + "-String.idx");
    }

    private static HashMap<String, Set<String>> byValue(List<FieldIndexEntry<String>> index) {
        final var result = new HashMap<String, Set<String>>();
        for (var e : index) {
            result.put(e.getValue(), e.getIds());
        }
        return result;
    }

    @Test
    public void test_field_index_round_trip_with_accented_value() throws Exception {
        final var fileSystem = fs();

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(ACCENTED, "id1"), null);

        List<FieldIndexEntry<String>> index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL,
                FIELD, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals(ACCENTED, index.getFirst().getValue());
        assertEquals(Set.of("id1"), index.getFirst().getIds());
    }

    @Test
    public void test_index_file_is_valid_utf8_after_update() throws Exception {
        final var fileSystem = fs();

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(CJK, "id1"), null);

        final var raw = Files.readAllBytes(indexFile().toPath());
        final var expected = (CJK + Globals.ID_SEPARATOR + "id1").getBytes(StandardCharsets.UTF_8);
        assertTrue(raw.length >= expected.length, "index file should hold the encoded entry");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], raw[i], "byte " + i + " must be valid UTF-8, not a truncated char");
        }
        assertDoesNotThrow(() -> Files.readAllLines(indexFile().toPath(), StandardCharsets.UTF_8),
                "a strict UTF-8 decode of the index file must not throw");
    }

    @Test
    public void test_updating_a_non_ascii_entry_preserves_its_neighbours() throws Exception {
        final var fileSystem = fs();

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("aaa", "id1"), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(CJK, "id2"), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("zzz", "id3"), null);

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(CJK, "id2", "id4"),
                entry(CJK, "id2"));

        final var index = byValue(
                fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class));
        assertEquals(3, index.size(), "updating the middle entry must not drop or merge its neighbours");
        assertEquals(Set.of("id1"), index.get("aaa"));
        assertEquals(Set.of("id2", "id4"), index.get(CJK));
        assertEquals(Set.of("id3"), index.get("zzz"));
    }

    @Test
    public void test_removing_a_non_ascii_entry_preserves_its_neighbours() throws Exception {
        final var fileSystem = fs();

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("aaa", "id1"), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(ACCENTED, "id2"), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("zzz", "id3"), null);

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, null,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, ACCENTED, Set.of()));

        final var index = byValue(
                fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class));
        assertEquals(2, index.size());
        assertEquals(Set.of("id1"), index.get("aaa"));
        assertEquals(Set.of("id3"), index.get("zzz"));
        assertFalse(index.containsKey(ACCENTED));
    }

    @Test
    public void test_field_index_round_trip_with_surrogate_pair_value() throws Exception {
        final var fileSystem = fs();

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(EMOJI, "id1"), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(EMOJI, "id1", "id2"),
                entry(EMOJI, "id1"));

        final var index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals(EMOJI, index.getFirst().getValue());
        assertEquals(Set.of("id1", "id2"), index.getFirst().getIds());
    }

    @Test
    public void test_field_index_round_trip_with_non_ascii_ids() throws Exception {
        final var fileSystem = fs();

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("value", "idé", "id日"), null);

        final var index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals(Set.of("idé", "id日"), index.getFirst().getIds());
    }

    @Test
    public void test_mixed_ascii_and_non_ascii_values_all_survive() throws Exception {
        final var fileSystem = fs();

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("plain", "id1"), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(ACCENTED, "id2"), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(CJK, "id3"), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(EMOJI, "id4"), null);

        final var index = byValue(
                fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class));
        assertEquals(4, index.size());
        assertEquals(Set.of("id1"), index.get("plain"));
        assertEquals(Set.of("id2"), index.get(ACCENTED));
        assertEquals(Set.of("id3"), index.get(CJK));
        assertEquals(Set.of("id4"), index.get(EMOJI));
    }
}
