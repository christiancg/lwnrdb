package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.*;

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

public class FieldIndexStoreLineEndingTest {
    private static final String DB = "endingsdb";
    private static final String COLL = "docs";
    private static final String FIELD = "tag";

    private static final List<String> TERMINATORS = List.of("\n", "\r\n");

    private record Fixture(FieldIndexStore store, FieldIndexLoader loader, File indexFile) {
    }

    private interface Scenario {
        void run(Fixture fixture, String terminator) throws IOException;
    }

    private static void underEachTerminator(File tmp, Scenario scenario) throws IOException {
        for (var i = 0; i < TERMINATORS.size(); i++) {
            final var root = new File(tmp, "run" + i);
            assertTrue(root.mkdirs());
            scenario.run(fixture(root), TERMINATORS.get(i));
        }
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

    private static void writeThreeValues(Fixture fixture, String terminator) throws IOException {
        final var content = entry("alpha", "a").toFileEntry() + terminator + entry("beta", "b").toFileEntry()
                + terminator + entry("gamma", "c").toFileEntry() + terminator;
        Files.writeString(fixture.indexFile().toPath(), content, StandardCharsets.UTF_8);
    }

    private static Map<String, Set<String>> idsByValue(Fixture fixture) throws IOException {
        final var loaded = fixture.loader().readWholeFieldIndexFiles(DB, COLL, FIELD, String.class);
        assertNotNull(loaded);
        final var byValue = new HashMap<String, Set<String>>();
        loaded.forEach(e -> assertNull(byValue.put(e.getValue(), Set.copyOf(e.getIds())),
                "a value must have exactly one line, never a duplicate appended beside it"));
        return byValue;
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
