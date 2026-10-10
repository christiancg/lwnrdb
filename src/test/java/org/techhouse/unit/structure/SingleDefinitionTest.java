package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.techhouse.unit.structure.JavaSources.JavaSourceFile;

public class SingleDefinitionTest {
    private static final String OPS = "src/main/java/org/techhouse/ops/";
    private static final Set<String> SYNCED_FILE_MODES = Set.of("rwd", "rws");
    private static final Set<String> MAX_ENTRY_SIZE_READERS = Set.of(
            "src/main/java/org/techhouse/config/Configuration.java", OPS + "EntrySizeGuard.java",
            OPS + "TriggerRunLog.java");
    private static final Set<String> TRIGGER_STAGERS = Set.of(OPS + "OperationProcessor.java",
            OPS + "BulkSaveEffects.java", OPS + "tx/CommittedOpTriggers.java");
    private static final Pattern MAX_ENTRY_SIZE_READ = Pattern.compile("\\bgetMaxEntrySize\\(");
    private static final Pattern TRIGGER_STAGING = Pattern.compile("\\bTriggerHelper\\.(stage\\w*|runStaged)\\(");

    static Set<String> filesOpeningInASyncedMode(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (file.text().stringLiterals().stream().anyMatch(SYNCED_FILE_MODES::contains)) {
                found.add(file.relativePath());
            }
        }
        return found;
    }

    static Set<String> filesWhoseCodeMatches(List<JavaSourceFile> files, Pattern pattern) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (pattern.matcher(file.text().code()).find()) {
                found.add(file.relativePath());
            }
        }
        return found;
    }

    @Test
    public void test_no_file_is_opened_in_a_synced_mode() {
        assertTrue(filesOpeningInASyncedMode(JavaSources.under(JavaSources.MAIN)).isEmpty(),
                "Writes are not synced to the device by decision; open files with \"rw\", never \"rwd\"/\"rws\"");
    }

    @Test
    public void test_only_the_size_guard_and_the_trigger_chunker_read_max_entry_size() {
        assertEquals(MAX_ENTRY_SIZE_READERS,
                filesWhoseCodeMatches(JavaSources.under(JavaSources.MAIN), MAX_ENTRY_SIZE_READ),
                "maxEntrySize is refused in one place, EntrySizeGuard; call it instead of reading the limit");
    }

    @Test
    public void test_triggers_are_staged_only_from_the_write_handlers() {
        assertEquals(TRIGGER_STAGERS, filesWhoseCodeMatches(JavaSources.under(JavaSources.MAIN), TRIGGER_STAGING),
                "Write side effects fire from OperationProcessor's write handlers and the transaction commit, never "
                        + "from a write helper that replicated applies also reach");
    }

    @Test
    public void test_a_synthetic_synced_open_is_caught() {
        final var file = JavaSources.parse("X.java",
                "class X { void f() throws Exception { new java.io.RandomAccessFile(\"a\", \"rwd\"); } }");
        assertEquals(Set.of("X.java"), filesOpeningInASyncedMode(List.of(file)));
    }

    @Test
    public void test_a_synthetic_reader_or_stager_is_caught_but_not_inside_a_string_or_comment() {
        final var reader = JavaSources.parse("Reader.java",
                "class Reader { long f() { return c.getMaxEntrySize(); } }");
        final var mentioned = JavaSources.parse("Mentioned.java",
                "class Mentioned { String s = \"getMaxEntrySize()\"; }\n// TriggerHelper.runStaged(x)\n");
        final var stager = JavaSources.parse("Stager.java",
                "class Stager { void f() { TriggerHelper.stageSave(r); } }");
        final var files = List.of(reader, mentioned, stager);
        assertEquals(Set.of("Reader.java"), filesWhoseCodeMatches(files, MAX_ENTRY_SIZE_READ));
        assertEquals(Set.of("Stager.java"), filesWhoseCodeMatches(files, TRIGGER_STAGING));
    }
}
