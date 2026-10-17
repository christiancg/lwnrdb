package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.fs.LegacyIndexLines;
import org.techhouse.test.TestGlobals;

public class FileSystemIndexUnicodeTest extends FileSystemTestBase {
    private static final String FIELD = "testField";
    private static final String ACCENTED = "café";
    private static final String CJK = "日本語";
    private static final String EMOJI = "a😀b";

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

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(ACCENTED, "id1"), null);

        List<FieldIndexEntry<String>> index = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals(ACCENTED, index.getFirst().getValue());
        assertEquals(Set.of("id1"), index.getFirst().getIds());
    }

    @Test
    public void test_index_file_is_valid_utf8_after_update() throws Exception {

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(CJK, "id1"), null);

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

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("aaa", "id1"), null);
        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(CJK, "id2"), null);
        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("zzz", "id3"), null);

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(CJK, "id2", "id4"),
                entry(CJK, "id2"));

        final var index = byValue(fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class));
        assertEquals(3, index.size(), "updating the middle entry must not drop or merge its neighbours");
        assertEquals(Set.of("id1"), index.get("aaa"));
        assertEquals(Set.of("id2", "id4"), index.get(CJK));
        assertEquals(Set.of("id3"), index.get("zzz"));
    }

    @Test
    public void test_removing_a_non_ascii_entry_preserves_its_neighbours() throws Exception {

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("aaa", "id1"), null);
        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(ACCENTED, "id2"), null);
        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("zzz", "id3"), null);

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, null,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, ACCENTED, Set.of()));

        final var index = byValue(fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class));
        assertEquals(2, index.size());
        assertEquals(Set.of("id1"), index.get("aaa"));
        assertEquals(Set.of("id3"), index.get("zzz"));
        assertFalse(index.containsKey(ACCENTED));
    }

    @Test
    public void test_field_index_round_trip_with_surrogate_pair_value() throws Exception {

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(EMOJI, "id1"), null);
        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(EMOJI, "id1", "id2"),
                entry(EMOJI, "id1"));

        final var index = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals(EMOJI, index.getFirst().getValue());
        assertEquals(Set.of("id1", "id2"), index.getFirst().getIds());
    }

    @Test
    public void test_field_index_round_trip_with_non_ascii_ids() throws Exception {

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("value", "idé", "id日"),
                null);

        final var index = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals(Set.of("idé", "id日"), index.getFirst().getIds());
    }

    @Test
    public void test_mixed_ascii_and_non_ascii_values_all_survive() throws Exception {

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("plain", "id1"), null);
        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(ACCENTED, "id2"), null);
        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(CJK, "id3"), null);
        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(EMOJI, "id4"), null);

        final var index = byValue(fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class));
        assertEquals(4, index.size());
        assertEquals(Set.of("id1"), index.get("plain"));
        assertEquals(Set.of("id2"), index.get(ACCENTED));
        assertEquals(Set.of("id3"), index.get(CJK));
        assertEquals(Set.of("id4"), index.get(EMOJI));
    }

    @Test
    public void test_lone_surrogate_value_round_trips_through_index_file() throws Exception {
        final var value = "a\ud800b";

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(value, "id1"), null);

        final var index = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals(value, index.getFirst().getValue());
        assertDoesNotThrow(() -> Files.readAllLines(indexFile().toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_two_lone_surrogate_values_occupy_two_distinct_lines() throws Exception {

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("a\ud800b", "hi"),
                null);
        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("a\udc00b", "lo"),
                null);
        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("a?b", "plain"), null);

        final var index = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class);
        assertNotNull(index);
        final var values = byValue(index);
        assertEquals(3, values.size());
        assertEquals(Set.of("hi"), values.get("a\ud800b"));
        assertEquals(Set.of("lo"), values.get("a\udc00b"));
        assertEquals(Set.of("plain"), values.get("a?b"));
    }

    @Test
    public void test_updating_a_lone_surrogate_entry_leaves_siblings_intact() throws Exception {
        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("a\ud800b", "hi"),
                null);
        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("a?b", "plain"), null);

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry("zzz", "hi"),
                entry("a\ud800b"));

        final var index = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class);
        assertNotNull(index);
        final var values = byValue(index);
        assertEquals(Set.of("plain"), values.get("a?b"), "an unrelated value must survive the update");
        assertEquals(Set.of("hi"), values.get("zzz"));
    }

    @Test
    public void test_paired_surrogate_value_is_stored_raw() throws Exception {

        LegacyIndexLines.of(fs).updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, entry(EMOJI, "id1"), null);

        final var raw = Files.readString(indexFile().toPath(), StandardCharsets.UTF_8);
        assertTrue(raw.startsWith(EMOJI), "a paired surrogate must not be escaped");
    }
}
