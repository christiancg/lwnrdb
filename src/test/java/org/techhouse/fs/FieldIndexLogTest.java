package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.test.TestUtils;

public class FieldIndexLogTest {
    private static final String DB = "logdb";
    private static final String COLL = "docs";
    private static final String FIELD = "tag";

    @TempDir
    File tmp;

    private FieldIndexStore store;
    private FieldIndexLoader loader;
    private File base;
    private File log;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        final var paths = new FilePaths();
        paths.useDbPath(tmp.getAbsolutePath());
        assertTrue(new File(tmp, DB + File.separator + COLL).mkdirs());
        store = new FieldIndexStore(paths);
        loader = new FieldIndexLoader(paths);
        base = java.util.Objects.requireNonNull(paths.indexFile(DB, COLL, FIELD, "String"));
        log = FilePaths.logFileOf(base);
    }

    private static FieldIndexEntry<String> entry(String value, String... ids) {
        return new FieldIndexEntry<>(DB, COLL, value, new HashSet<>(Set.of(ids)));
    }

    private static IndexLogChange change(String value, List<String> added, List<String> removed) {
        return new IndexLogChange(FieldIndexEntry.fileKeyOf(value), added, removed);
    }

    private Map<String, Set<String>> loaded() throws IOException {
        final var entries = loader.readWholeFieldIndexFiles(DB, COLL, FIELD, String.class);
        if (entries == null) {
            return null;
        }
        final var byValue = new TreeMap<String, Set<String>>();
        entries.forEach(e -> byValue.put(e.getValue(), Set.copyOf(e.getIds())));
        return byValue;
    }

    @Test
    public void anAppendIsReplayedOverTheBase() throws IOException {
        FieldIndexStore.replaceType(base, log, List.of(entry("alpha", "a", "b"), entry("beta", "c")));

        final var compacted = store.appendIndexLog(DB, COLL, FIELD, "String",
                List.of(change("alpha", List.of(), List.of("a")), change("gamma", List.of("a"), List.of())),
                List.of(entry("alpha", "b"), entry("beta", "c"), entry("gamma", "a")));

        assertFalse(compacted);
        assertTrue(log.exists());
        assertEquals(Map.of("alpha", Set.of("b"), "beta", Set.of("c"), "gamma", Set.of("a")), loaded());
    }

    @Test
    public void aLogWithNoBaseIsAnIndex() throws IOException {
        store.appendIndexLog(DB, COLL, FIELD, "String", List.of(change("only", List.of("x"), List.of())),
                List.of(entry("only", "x")));

        assertFalse(base.exists());
        assertEquals(Map.of("only", Set.of("x")), loaded());
    }

    @Test
    public void aValueEmptiedByTheLogIsNotLoaded() throws IOException {
        FieldIndexStore.replaceType(base, log, List.of(entry("alpha", "a"), entry("beta", "b")));

        store.appendIndexLog(DB, COLL, FIELD, "String", List.of(change("alpha", List.of(), List.of("a"))),
                List.of(entry("beta", "b")));

        assertEquals(Map.of("beta", Set.of("b")), loaded());
    }

    @Test
    public void replayIsIdempotentAfterAKillBetweenACompactionsRenameAndItsLogDelete() throws IOException {
        FieldIndexStore.replaceType(base, log, List.of(entry("alpha", "a"), entry("beta", "b")));
        store.appendIndexLog(DB, COLL, FIELD, "String",
                List.of(change("alpha", List.of(), List.of("a")), change("beta", List.of("a"), List.of())),
                List.of(entry("beta", "a", "b")));
        final var logBytes = Files.readAllBytes(log.toPath());

        FieldIndexStore.replaceType(base, log, List.of(entry("beta", "a", "b")));
        assertFalse(log.exists(), "a compaction deletes the log it folded");
        Files.write(log.toPath(), logBytes);

        assertEquals(Map.of("beta", Set.of("a", "b")), loaded());
    }

    @Test
    public void aListThatEmptiesDeletesBothFiles() throws IOException {
        FieldIndexStore.replaceType(base, log, List.of(entry("alpha", "a")));
        store.appendIndexLog(DB, COLL, FIELD, "String", List.of(change("beta", List.of("b"), List.of())),
                List.of(entry("alpha", "a"), entry("beta", "b")));
        assertTrue(log.exists());

        assertTrue(store.appendIndexLog(DB, COLL, FIELD, "String",
                List.of(change("alpha", List.of(), List.of("a")), change("beta", List.of(), List.of("b"))), List.of()));

        assertFalse(base.exists());
        assertFalse(log.exists());
        assertNull(loaded(), "an index with no entries left has no file");
    }

    @Test
    public void aTornLastLogLineIsIgnored() throws IOException {
        store.appendIndexLog(DB, COLL, FIELD, "String", List.of(change("kept", List.of("k"), List.of())),
                List.of(entry("kept", "k")));
        Files.writeString(log.toPath(), "+" + Globals.ID_SEPARATOR + "torn" + Globals.ID_SEPARATOR + "t",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertEquals(Map.of("kept", Set.of("k")), loaded());

        store.appendIndexLog(DB, COLL, FIELD, "String", List.of(change("next", List.of("n"), List.of())),
                List.of(entry("kept", "k"), entry("next", "n")));
        assertEquals(Map.of("kept", Set.of("k"), "next", Set.of("n")), loaded(),
                "the next append cuts the torn line instead of joining it");
    }

    @Test
    public void anUnrecognisedLogDeclinesTheIndex() throws IOException {
        FieldIndexStore.replaceType(base, log, List.of(entry("alpha", "a")));
        Files.writeString(log.toPath(), "garbage\nmore garbage\n", StandardCharsets.UTF_8);

        assertNull(loaded(), "a log no line of which parses is never read as no changes");
    }

    @Test
    public void aMalformedLogLineAmongGoodOnesIsSkipped() throws IOException {
        FieldIndexStore.replaceType(base, log, List.of(entry("alpha", "a")));
        Files.writeString(log.toPath(), "garbage\n+" + Globals.ID_SEPARATOR + "beta" + Globals.ID_SEPARATOR + "b\n",
                StandardCharsets.UTF_8);

        assertEquals(Map.of("alpha", Set.of("a"), "beta", Set.of("b")), loaded());
    }

    @Test
    public void theLogIsCompactedOnceItOutgrowsTheBaseAndTheFloor() throws IOException {
        FieldIndexStore.replaceType(base, log, List.of(entry("alpha", "a")));
        final var survivors = new ArrayList<FieldIndexEntry<?>>();
        survivors.add(entry("alpha", "a"));
        var compacted = false;
        var round = 0;
        while (!compacted) {
            final var value = "value-" + round++;
            survivors.add(entry(value, "id-" + round));
            compacted = store.appendIndexLog(DB, COLL, FIELD, "String",
                    List.of(change(value, List.of("id-" + round), List.of())), survivors);
            assertTrue(round < 100_000, "the log never compacted");
        }

        assertFalse(log.exists());
        assertTrue(base.length() >= Globals.FIELD_INDEX_LOG_MIN_BYTES / 2);
        final var reloaded = loaded();
        assertNotNull(reloaded);
        assertEquals(survivors.size(), reloaded.size());
    }

    @Test
    public void dropIndexRemovesTheLogToo() throws IOException {
        store.appendIndexLog(DB, COLL, FIELD, "String", List.of(change("alpha", List.of("a"), List.of())),
                List.of(entry("alpha", "a")));

        assertTrue(store.dropIndex(DB, COLL, FIELD));

        assertFalse(log.exists());
    }

    @Test
    public void aFullBuildDeletesAStaleLog() throws IOException {
        store.appendIndexLog(DB, COLL, FIELD, "String", List.of(change("stale", List.of("s"), List.of())),
                List.of(entry("stale", "s")));

        store.writeIndexFile(DB, COLL, FIELD, Map.of(String.class, List.of(entry("fresh", "f"))));

        assertFalse(log.exists());
        assertEquals(Map.of("fresh", Set.of("f")), loaded());
    }

    private void appendTo(File target) throws IOException {
        FieldIndexLog.append(base, target, List.of(change("alpha", List.of("a"), List.of())));
    }

    private List<String> forcedBy(RecordingForcer recorder) throws IOException {
        recorder.clear();
        final var group = new ForceGroup();
        FieldIndexLog.forceUnforced(log.getParentFile(), group);
        group.force();
        return recorder.forcedNames();
    }

    @Test
    public void test_an_append_is_not_forced() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            appendTo(log);

            assertFalse(recorder.forcedNames().contains(log.getName()), recorder.forcedNames().toString());
        }
    }

    @Test
    public void test_force_unforced_forces_each_log_once_and_forgets_it() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            appendTo(log);
            appendTo(log);

            assertEquals(List.of(log.getName()), forcedBy(recorder));
            assertEquals(List.of(), forcedBy(recorder));
        }
    }

    @Test
    public void test_an_append_racing_the_force_stays_unforced() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            appendTo(log);
            final var group = new ForceGroup();
            FieldIndexLog.forceUnforced(log.getParentFile(), group);
            appendTo(log);
            group.force();

            assertEquals(List.of(log.getName()), forcedBy(recorder), "the later append is forced by the next pass");
        }
    }

    @Test
    public void test_a_failed_force_keeps_the_log_unforced() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            appendTo(log);
            recorder.failingFor(".idxlog");
            final var group = new ForceGroup();
            FieldIndexLog.forceUnforced(log.getParentFile(), group);
            assertThrows(IOException.class, group::force);
            recorder.failingFor(null);

            assertEquals(List.of(log.getName()), forcedBy(recorder));
        }
    }

    @Test
    public void test_a_deleted_log_is_skipped() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            appendTo(log);
            Files.delete(log.toPath());

            assertEquals(List.of(), forcedBy(recorder));
        }
    }

    @Test
    public void test_a_dropped_folder_forgets_its_logs() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            appendTo(log);
            FieldIndexLog.forgetUnder(log.getParentFile().getParentFile());

            assertEquals(List.of(), forcedBy(recorder));
        }
    }

    private static int tornLineChecksDuring(IoStep step) throws IOException {
        final var checks = new java.util.concurrent.atomic.AtomicInteger();
        try (var locks = mockStatic(FileLocks.class, CALLS_REAL_METHODS)) {
            locks.when(() -> FileLocks.cutUnterminatedTail(any(File.class))).thenAnswer(invocation -> {
                checks.incrementAndGet();
                return invocation.callRealMethod();
            });
            step.run();
        }
        return checks.get();
    }

    @FunctionalInterface
    private interface IoStep {
        void run() throws IOException;
    }

    @Test
    public void test_a_known_tail_skips_the_torn_line_check() throws IOException {
        assertEquals(1, tornLineChecksDuring(() -> store.appendIndexLog(DB, COLL, FIELD, "String",
                List.of(change("one", List.of("a"), List.of())), List.of(entry("one", "a")))));

        assertEquals(0, tornLineChecksDuring(() -> store.appendIndexLog(DB, COLL, FIELD, "String",
                List.of(change("two", List.of("b"), List.of())), List.of(entry("one", "a"), entry("two", "b")))));

        assertEquals(Map.of("one", Set.of("a"), "two", Set.of("b")), loaded());
        assertEquals(log.length(), PageTails.knownTerminatedLength(log));
    }

    @Test
    public void test_an_externally_changed_log_is_checked_again() throws IOException {
        store.appendIndexLog(DB, COLL, FIELD, "String", List.of(change("kept", List.of("k"), List.of())),
                List.of(entry("kept", "k")));
        Files.writeString(log.toPath(), "+" + Globals.ID_SEPARATOR + "torn", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        assertEquals(1, tornLineChecksDuring(() -> store.appendIndexLog(DB, COLL, FIELD, "String",
                List.of(change("next", List.of("n"), List.of())), List.of(entry("kept", "k"), entry("next", "n")))));

        assertFalse(Files.readString(log.toPath()).contains("torn"), "the torn line is cut before the append");
        assertEquals(Map.of("kept", Set.of("k"), "next", Set.of("n")), loaded());
    }

    @Test
    public void test_a_folded_log_is_forgotten() throws IOException {
        store.appendIndexLog(DB, COLL, FIELD, "String", List.of(change("kept", List.of("k"), List.of())),
                List.of(entry("kept", "k")));
        assertTrue(PageTails.knownTerminatedLength(log) > 0);

        FieldIndexStore.replaceType(base, log, List.of(entry("kept", "k")));

        assertFalse(log.exists());
        assertEquals(-1L, PageTails.knownTerminatedLength(log));
    }
}
