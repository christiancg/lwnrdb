package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.FieldIndexEntry;

public class FieldIndexStoreAtomicWriteTest {
    private static final String DB = "atomicdb";
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
        loaded.forEach(e -> byValue.put(e.getValue(), Set.copyOf(e.getIds())));
        return byValue;
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
}
