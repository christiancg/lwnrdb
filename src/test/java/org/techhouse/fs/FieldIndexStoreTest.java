package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.FieldIndexEntry;

public class FieldIndexStoreTest {
    private static final String DB = "storedb";
    private static final String COLL = "docs";
    private static final String FIELD = "tag";

    private static final List<String> TERMINATORS = List.of("\n", "\r\n");

    private record Fixture(FieldIndexStore store, FieldIndexLoader loader, File indexFile) {
    }

    private interface Scenario {
        void run(Fixture fixture, String terminator) throws IOException;
    }

    private static Fixture fixture(File tmp) {
        final var paths = new FilePaths();
        paths.useDbPath(tmp.getAbsolutePath());
        assertTrue(new File(tmp, DB + File.separator + COLL).mkdirs());
        return new Fixture(new FieldIndexStore(paths), new FieldIndexLoader(paths),
                paths.indexFile(DB, COLL, FIELD, "String"));
    }

    private static FieldIndexEntry<String> entry(String value, String... ids) {
        return new FieldIndexEntry<>(DB, COLL, value, new HashSet<>(Set.of(ids)));
    }

    private static Map<String, Set<String>> idsByValue(Fixture fixture) throws IOException {
        final var loaded = fixture.loader().readWholeFieldIndexFiles(DB, COLL, FIELD, String.class);
        assertNotNull(loaded);
        final var byValue = new HashMap<String, Set<String>>();
        loaded.forEach(e -> assertNull(byValue.put(e.getValue(), Set.copyOf(e.getIds())),
                "a value must have exactly one line, never a duplicate appended beside it"));
        return byValue;
    }

    private static void underEachTerminator(File tmp, Scenario scenario) throws IOException {
        for (var i = 0; i < TERMINATORS.size(); i++) {
            final var root = new File(tmp, "run" + i);
            assertTrue(root.mkdirs());
            scenario.run(fixture(root), TERMINATORS.get(i));
        }
    }

    private static void blockAtomicRewrite(Fixture fixture) throws IOException {
        final var tempTarget = new File(fixture.indexFile().getPath() + ".repair");
        assertTrue(tempTarget.mkdir());
        Files.writeString(new File(tempTarget, "occupied").toPath(), "x");
    }

    private static void seedThreeValues(Fixture fixture) throws IOException {
        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("alpha", "a"), null);
        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("beta", "b"), null);
        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("gamma", "c"), null);
    }

    @Test
    public void test_upsert_replaces_the_existing_line_and_keeps_the_others(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        seedThreeValues(fixture);

        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("beta", "b", "d"), null);

        assertEquals(Map.of("alpha", Set.of("a"), "beta", Set.of("b", "d"), "gamma", Set.of("c")), idsByValue(fixture));
        assertEquals(3, Files.readAllLines(fixture.indexFile().toPath()).size());
    }

    @Test
    public void test_upsert_of_the_first_and_last_lines(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        seedThreeValues(fixture);

        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("alpha", "a", "x"), null);
        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("gamma", "c", "y"), null);

        assertEquals(Map.of("alpha", Set.of("a", "x"), "beta", Set.of("b"), "gamma", Set.of("c", "y")),
                idsByValue(fixture));
    }

    @Test
    public void test_upsert_creates_an_absent_file(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        assertFalse(fixture.indexFile().exists());

        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("only", "a"), null);

        assertEquals(Map.of("only", Set.of("a")), idsByValue(fixture));
    }

    @Test
    public void test_upsert_after_a_last_line_without_a_trailing_newline(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("first", "a"), null);
        final var bytes = Files.readAllBytes(fixture.indexFile().toPath());
        final var trimmed = new String(bytes, StandardCharsets.UTF_8).stripTrailing();
        Files.writeString(fixture.indexFile().toPath(), trimmed);

        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("first", "a", "b"), null);

        assertEquals(Map.of("first", Set.of("a", "b")), idsByValue(fixture));
    }

    @Test
    public void test_removing_an_absent_value_leaves_the_file_untouched(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        seedThreeValues(fixture);
        final var before = Files.readAllBytes(fixture.indexFile().toPath());

        fixture.store().updateIndexFiles(DB, COLL, FIELD, null, entry("missing"));

        assertArrayEquals(before, Files.readAllBytes(fixture.indexFile().toPath()));
    }

    @Test
    public void test_a_failed_upsert_leaves_every_value_indexed(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        seedThreeValues(fixture);
        final var before = Files.readAllBytes(fixture.indexFile().toPath());
        blockAtomicRewrite(fixture);

        assertThrows(IOException.class,
                () -> fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("beta", "b", "d"), null));

        assertArrayEquals(before, Files.readAllBytes(fixture.indexFile().toPath()),
                "a write that failed must not have removed the line it was replacing");
    }

    @Test
    public void test_a_failed_removal_leaves_every_value_indexed(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("shared", "a", "b"), null);
        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("other", "c"), null);
        final var before = Files.readAllBytes(fixture.indexFile().toPath());
        blockAtomicRewrite(fixture);

        assertThrows(IOException.class,
                () -> fixture.store().updateIndexFiles(DB, COLL, FIELD, null, entry("shared", "b")));

        assertArrayEquals(before, Files.readAllBytes(fixture.indexFile().toPath()));
    }

    @Test
    public void test_a_failed_build_leaves_no_partial_file(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        blockAtomicRewrite(fixture);
        final Map<Class<?>, List<FieldIndexEntry<?>>> entries = Map.of(String.class,
                List.of(entry("alpha", "a"), entry("beta", "b")));

        assertThrows(RuntimeException.class, () -> fixture.store().writeIndexFile(DB, COLL, FIELD, entries));

        assertFalse(fixture.indexFile().exists(), "a failed build must leave the type file absent, never partial");
    }

    @Test
    public void test_a_build_writes_every_entry(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        final Map<Class<?>, List<FieldIndexEntry<?>>> entries = Map.of(String.class,
                List.of(entry("alpha", "a"), entry("beta", "b", "c")));

        fixture.store().writeIndexFile(DB, COLL, FIELD, entries);

        assertEquals(Map.of("alpha", Set.of("a"), "beta", Set.of("b", "c")), idsByValue(fixture));
    }

    @Test
    public void test_removing_the_last_line_deletes_the_index_file(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("only", "a"), null);
        assertTrue(fixture.indexFile().exists());

        fixture.store().updateIndexFiles(DB, COLL, FIELD, null, entry("only"));

        assertFalse(fixture.indexFile().exists(), "an index with no entries left must not survive as an empty file");
        assertNull(fixture.loader().readWholeFieldIndexFiles(DB, COLL, FIELD, String.class),
                "an absent index must read as absent, not as an empty list");
    }

    @Test
    public void test_removing_one_of_two_lines_keeps_the_file(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("first", "a"), null);
        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("second", "b"), null);

        fixture.store().updateIndexFiles(DB, COLL, FIELD, null, entry("first"));

        assertTrue(fixture.indexFile().exists());
        final var survivors = fixture.loader().readWholeFieldIndexFiles(DB, COLL, FIELD, String.class);
        assertNotNull(survivors);
        assertEquals(1, survivors.size());
        assertEquals("second", survivors.getFirst().getValue());
        assertEquals(Set.of("b"), survivors.getFirst().getIds());
    }

    @Test
    public void test_a_line_that_still_has_ids_keeps_the_file(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("shared", "a", "b"), null);

        fixture.store().updateIndexFiles(DB, COLL, FIELD, null, entry("shared", "b"));

        assertTrue(fixture.indexFile().exists());
        final var survivors = fixture.loader().readWholeFieldIndexFiles(DB, COLL, FIELD, String.class);
        assertNotNull(survivors);
        assertEquals(List.of("b"), List.copyOf(survivors.getFirst().getIds()));
    }

    private static Thread repeatedReader(Fixture fixture, CountDownLatch start, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                start.await();
                for (var i = 0; i < 200; i++) {
                    final var loaded = fixture.loader().readWholeFieldIndexFiles(DB, COLL, FIELD, String.class);
                    if (loaded != null && !loaded.isEmpty()) {
                        assertEquals("only", loaded.getFirst().getValue());
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
    }

    @Test
    public void test_a_concurrent_reader_never_sees_a_half_deleted_file(@TempDir File tmp) throws Exception {
        final var fixture = fixture(tmp);
        fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("only", "a"), null);
        final var failure = new AtomicReference<Throwable>();
        final var start = new CountDownLatch(1);

        final var reader = repeatedReader(fixture, start, failure);
        reader.start();
        start.countDown();
        fixture.store().updateIndexFiles(DB, COLL, FIELD, null, entry("only"));
        assertTrue(reader.join(Duration.ofSeconds(10)), "the reader must finish");

        assertNull(failure.get(), String.valueOf(failure.get()));
        assertFalse(fixture.indexFile().exists());
    }

    private static void writeThreeValues(Fixture fixture, String terminator) throws IOException {
        final var content = entry("alpha", "a").toFileEntry() + terminator + entry("beta", "b").toFileEntry()
                + terminator + entry("gamma", "c").toFileEntry() + terminator;
        Files.writeString(fixture.indexFile().toPath(), content, StandardCharsets.UTF_8);
    }

    @Test
    public void test_updating_the_first_value_keeps_every_other_value(@TempDir File tmp) throws IOException {
        underEachTerminator(tmp, (fixture, terminator) -> {
            writeThreeValues(fixture, terminator);

            fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("alpha", "a", "a2"), null);

            assertEquals(Map.of("alpha", Set.of("a", "a2"), "beta", Set.of("b"), "gamma", Set.of("c")),
                    idsByValue(fixture), "an index file written under another platform's line ending must not be"
                            + " read as a single line, which used to rewrite it down to the updated value alone");
        });
    }

    @Test
    public void test_updating_a_later_value_replaces_its_line(@TempDir File tmp) throws IOException {
        underEachTerminator(tmp, (fixture, terminator) -> {
            writeThreeValues(fixture, terminator);

            fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("gamma", "c", "c2"), null);

            assertEquals(Map.of("alpha", Set.of("a"), "beta", Set.of("b"), "gamma", Set.of("c", "c2")),
                    idsByValue(fixture));
        });
    }

    @Test
    public void test_removing_the_last_id_of_a_middle_value_drops_only_its_line(@TempDir File tmp) throws IOException {
        underEachTerminator(tmp, (fixture, terminator) -> {
            writeThreeValues(fixture, terminator);

            fixture.store().updateIndexFiles(DB, COLL, FIELD, null, entry("beta"));

            assertEquals(Map.of("alpha", Set.of("a"), "gamma", Set.of("c")), idsByValue(fixture));
        });
    }

    @Test
    public void test_a_file_mixing_both_line_endings_is_framed_line_by_line(@TempDir File tmp) throws IOException {
        underEachTerminator(tmp, (fixture, terminator) -> {
            final var content = entry("alpha", "a").toFileEntry() + "\r\n" + entry("beta", "b").toFileEntry() + "\n"
                    + entry("gamma", "c").toFileEntry() + terminator;
            Files.writeString(fixture.indexFile().toPath(), content, StandardCharsets.UTF_8);

            fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("beta", "b", "b2"), null);

            assertEquals(Map.of("alpha", Set.of("a"), "beta", Set.of("b", "b2"), "gamma", Set.of("c")),
                    idsByValue(fixture));
        });
    }

    @Test
    public void test_appending_after_an_unterminated_last_line_starts_a_new_line(@TempDir File tmp) throws IOException {
        underEachTerminator(tmp, (fixture, terminator) -> {
            final var content = entry("alpha", "a").toFileEntry() + terminator + entry("beta", "b").toFileEntry();
            Files.writeString(fixture.indexFile().toPath(), content, StandardCharsets.UTF_8);

            fixture.store().updateIndexFiles(DB, COLL, FIELD, entry("delta", "d"), null);

            assertEquals(Map.of("alpha", Set.of("a"), "beta", Set.of("b"), "delta", Set.of("d")), idsByValue(fixture),
                    "a new line must not be glued onto a last line that lacks its terminator");
        });
    }
}
