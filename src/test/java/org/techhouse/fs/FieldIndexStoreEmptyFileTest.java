package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.FieldIndexEntry;

public class FieldIndexStoreEmptyFileTest {
    private static final String DB = "emptydb";
    private static final String COLL = "docs";
    private static final String FIELD = "tag";

    private record Fixture(FilePaths paths, FieldIndexStore store, FieldIndexLoader loader, File indexFile) {
    }

    private static Fixture fixture(File tmp) {
        final var paths = new FilePaths();
        paths.useDbPath(tmp.getAbsolutePath());
        assertTrue(new File(tmp, DB + File.separator + COLL).mkdirs());
        return new Fixture(paths, new FieldIndexStore(paths), new FieldIndexLoader(paths),
                paths.indexFile(DB, COLL, FIELD, "String"));
    }

    private static FieldIndexEntry<String> entry(String value, String... ids) {
        return new FieldIndexEntry<>(DB, COLL, value, new HashSet<>(Set.of(ids)));
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
        assertTrue(reader.join(java.time.Duration.ofSeconds(10)), "the reader must finish");

        assertNull(failure.get(), String.valueOf(failure.get()));
        assertFalse(fixture.indexFile().exists());
    }
}
