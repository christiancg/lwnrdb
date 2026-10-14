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
import java.nio.file.attribute.PosixFilePermission;
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
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;

public class FieldIndexStoreTest {
    private static final String DB = "storedb";
    private static final String COLL = "docs";
    private static final String FIELD = "tag";

    private record Fixture(FieldIndexStore store, FieldIndexLoader loader, File indexFile) {
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

    private static void blockAtomicRewrite(Fixture fixture) throws IOException {
        final var tempTarget = new File(fixture.indexFile().getPath() + ".repair");
        assertTrue(tempTarget.mkdir());
        Files.writeString(new File(tempTarget, "occupied").toPath(), "x");
    }

    private static void rewrite(Fixture fixture, List<FieldIndexEntry<String>> entries) throws IOException {
        fixture.store().rewriteIndexType(DB, COLL, FIELD, "String", entries);
    }

    @Test
    public void test_a_rewrite_writes_every_entry_in_the_given_order(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);

        rewrite(fixture, List.of(entry("beta", "b"), entry("alpha", "a")));

        assertEquals("beta" + Globals.ID_SEPARATOR + "b" + Globals.NEWLINE + "alpha" + Globals.ID_SEPARATOR + "a"
                + Globals.NEWLINE, Files.readString(fixture.indexFile().toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_a_rewrite_replaces_every_previous_line(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        rewrite(fixture, List.of(entry("alpha", "a"), entry("beta", "b")));

        rewrite(fixture, List.of(entry("gamma", "c", "d")));

        assertEquals(Map.of("gamma", Set.of("c", "d")), idsByValue(fixture));
    }

    @Test
    public void test_a_failed_rewrite_leaves_every_value_indexed(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        rewrite(fixture, List.of(entry("alpha", "a"), entry("beta", "b"), entry("gamma", "c")));
        final var before = Files.readAllBytes(fixture.indexFile().toPath());
        blockAtomicRewrite(fixture);

        assertThrows(IOException.class, () -> rewrite(fixture, List.of(entry("beta", "b", "d"))));

        assertArrayEquals(before, Files.readAllBytes(fixture.indexFile().toPath()),
                "a write that failed must not have touched the file it was replacing");
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
    public void test_a_rewrite_with_no_entries_deletes_the_index_file(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);
        rewrite(fixture, List.of(entry("only", "a")));
        assertTrue(fixture.indexFile().exists());

        rewrite(fixture, List.of());

        assertFalse(fixture.indexFile().exists(), "an index with no entries left must not survive as an empty file");
        assertNull(fixture.loader().readWholeFieldIndexFiles(DB, COLL, FIELD, String.class),
                "an absent index must read as absent, not as an empty list");
    }

    @Test
    public void test_an_empty_rewrite_of_an_absent_file_creates_nothing(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);

        rewrite(fixture, List.of());

        assertFalse(fixture.indexFile().exists());
    }

    @Test
    public void test_a_rewrite_creates_its_file_owner_only(@TempDir File tmp) throws IOException {
        final var fixture = fixture(tmp);

        rewrite(fixture, List.of(entry("alpha", "a")));

        final var permissions = Files.getPosixFilePermissions(fixture.indexFile().toPath());
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
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
        rewrite(fixture, List.of(entry("only", "a")));
        final var failure = new AtomicReference<Throwable>();
        final var start = new CountDownLatch(1);

        final var reader = repeatedReader(fixture, start, failure);
        reader.start();
        start.countDown();
        rewrite(fixture, List.of());
        assertTrue(reader.join(Duration.ofSeconds(10)), "the reader must finish");

        assertNull(failure.get(), String.valueOf(failure.get()));
        assertFalse(fixture.indexFile().exists());
    }
}
