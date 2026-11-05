package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.test.TestUtils;

public class IndexLogMemoTest {
    private static final String DB = "memodb";
    private static final String COLL = "docs";
    private static final String FIELD = "tag";
    private static final String US = Globals.ID_SEPARATOR;
    private static final Predicate<Object> EVERY = _ -> true;

    @TempDir
    File tmp;

    private FieldIndexStore store;
    private File base;
    private File log;
    private IndexLogMemo memo;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        final var paths = new FilePaths();
        paths.useDbPath(tmp.getAbsolutePath());
        assertTrue(new File(tmp, DB + File.separator + COLL).mkdirs());
        store = new FieldIndexStore(paths);
        base = java.util.Objects.requireNonNull(paths.indexFile(DB, COLL, FIELD, "String"));
        log = FilePaths.logFileOf(base);
        memo = new IndexLogMemo(Globals.INDEX_LOG_MEMO_MAX_BYTES);
    }

    private static String added(String key, String... ids) {
        return "+" + US + key + US + String.join(US, ids) + "\n";
    }

    private static void write(File file, String text, StandardOpenOption... options) throws IOException {
        Files.writeString(file.toPath(), text, StandardCharsets.UTF_8, options);
    }

    private Map<String, Set<String>> answer(IndexLogMemo from, File target, Predicate<Object> selects)
            throws IOException {
        final var overlay = from.overlayOf(target, IndexKeyOrder.of(String.class), selects);
        final var byKey = new TreeMap<String, Set<String>>();
        for (final var key : overlay.keys()) {
            byKey.put(key, overlay.applied(key, null));
        }
        return byKey;
    }

    private Map<String, Set<String>> answer() throws IOException {
        return answer(memo, log, EVERY);
    }

    private static IndexLogChange change(String value, List<String> adds, List<String> removes) {
        return new IndexLogChange(FieldIndexEntry.fileKeyOf(value), adds, removes);
    }

    private static FieldIndexEntry<String> entry(String value, String... ids) {
        return new FieldIndexEntry<>(DB, COLL, value, new HashSet<>(Set.of(ids)));
    }

    @Test
    public void parsesOnceAndExtendsFromTheTail() throws IOException {
        final var first = added("alpha", "a1", "a2");
        write(log, first + added("beta", "b1") + "-" + US + "alpha" + US + "a2\n" + added("gamma", "g1", "g2", "g3"));
        assertEquals(Map.of("alpha", Set.of("a1"), "beta", Set.of("b1"), "gamma", Set.of("g1", "g2", "g3")), answer());

        try (var raw = new RandomAccessFile(log, "rw")) {
            raw.write("#".repeat(first.length() - 1).getBytes(StandardCharsets.UTF_8));
        }
        write(log, added("delta", "d1"), StandardOpenOption.APPEND);

        assertEquals(Map.of("alpha", Set.of("a1"), "beta", Set.of("b1"), "gamma", Set.of("g1", "g2", "g3"), "delta",
                Set.of("d1")), answer(), "only the appended tail is parsed, the memoised prefix is reused");
    }

    private static final String PADDING = added("padding", "p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "p10",
            "p11", "p12");

    @Test
    public void aFoldInvalidates() throws IOException {
        FieldIndexStore.replaceType(base, log, List.of(entry("alpha", "a")));
        write(log, added("beta", "b1") + PADDING);
        assertEquals(Map.of("beta", Set.of("b1"), "padding",
                Set.of("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "p10", "p11", "p12")), answer());

        FieldIndexStore.replaceType(base, log, List.of(entry("alpha", "a"), entry("beta", "b1")));
        assertFalse(log.exists());
        write(log, added("bOta", "b2") + PADDING);

        assertEquals(Set.of("b2"), answer().get("bOta"),
                "a log of the same length and tail after a fold is parsed again, by its generation");
    }

    @Test
    public void aRollbackInvalidates() throws IOException {
        store.appendIndexLog(DB, COLL, FIELD, "String", List.of(change("kept", List.of("k"), List.of())),
                List.of(entry("kept", "k")), 1);
        write(log, PADDING, StandardOpenOption.APPEND);
        assertEquals(Set.of("k"), answer().get("kept"));
        final var kept = Files.readString(log.toPath());
        final var generation = FieldIndexLog.generationOf(log);
        Files.delete(log.toPath());
        assertTrue(log.mkdir());

        assertThrows(IOException.class, () -> store.appendIndexLog(DB, COLL, FIELD, "String",
                List.of(change("lost", List.of("l"), List.of())), List.of(entry("kept", "k"), entry("lost", "l")), 1));

        assertTrue(FieldIndexLog.generationOf(log) > generation);
        assertTrue(log.delete());
        write(log, kept.replace("kept", "KEPT"));
        assertEquals(Set.of("k"), answer().get("KEPT"));
        assertFalse(answer().containsKey("kept"));
    }

    @Test
    public void aDropAndABuildInvalidate() throws IOException {
        write(log, added("alpha", "a1") + PADDING);
        assertEquals(Set.of("a1"), answer().get("alpha"));

        assertTrue(store.dropIndex(DB, COLL, FIELD));
        write(log, added("alphA", "a2") + PADDING);
        assertEquals(Set.of("a2"), answer().get("alphA"), "a dropped log is never extended from its old length");

        store.writeIndexFile(DB, COLL, FIELD, Map.of(String.class, List.of(entry("fresh", "f"))));
        assertFalse(log.exists());
        write(log, added("Alpha", "a3") + PADDING);
        assertEquals(Set.of("a3"), answer().get("Alpha"), "a rebuilt index's log is parsed again");
    }

    @Test
    public void anUnterminatedLineDeclinesAndDropsTheEntry() throws IOException {
        write(log, added("alpha", "a1"));
        assertEquals(Map.of("alpha", Set.of("a1")), answer());
        assertTrue(memo.holds(log));

        write(log, "+" + US + "torn" + US + "t", StandardOpenOption.APPEND);

        assertThrows(IndexFileDeclined.class, this::answer);
        assertFalse(memo.holds(log));
        assertEquals(0L, memo.bytes());
        assertThrows(IndexFileDeclined.class, this::answer, "a torn tail declines on every lookup until it is cut");
    }

    @Test
    public void anUnsignedOrUnparsableLineDeclinesAndDropsTheEntry() throws IOException {
        write(log, added("alpha", "a1"));
        assertEquals(Map.of("alpha", Set.of("a1")), answer());

        write(log, "*" + US + "beta" + US + "b1\n", StandardOpenOption.APPEND);
        assertThrows(IndexFileDeclined.class, this::answer);
        assertFalse(memo.holds(log));

        write(log, "+" + US + "x\n");
        assertThrows(IndexFileDeclined.class, () -> memo.overlayOf(log, IndexKeyOrder.of(Number.class), EVERY));
        assertFalse(memo.holds(log));
    }

    @Test
    public void answersAreFreshSetsPerCall() throws IOException {
        write(log, added("alpha", "a1", "a2") + added("beta", "b1"));

        final var first = memo.overlayOf(log, IndexKeyOrder.of(String.class), EVERY);
        final var second = memo.overlayOf(log, IndexKeyOrder.of(String.class), EVERY);
        final var firstIds = first.applied("alpha", null);
        final var secondIds = second.applied("alpha", null);
        assertNotSame(firstIds, secondIds);
        firstIds.clear();

        assertEquals(Set.of("a1", "a2"), secondIds);
        assertEquals(Set.of("a1", "a2"),
                memo.overlayOf(log, IndexKeyOrder.of(String.class), EVERY).applied("alpha", new HashSet<>()));
    }

    @Test
    public void onlySelectedKeysReachTheOverlay() throws IOException {
        write(log, added("alpha", "a1") + added("ALPHA", "a2") + added("beta", "b1"));

        assertEquals(Map.of("ALPHA", Set.of("a2"), "alpha", Set.of("a1")),
                answer(memo, log, value -> "alpha".equalsIgnoreCase((String) value)));
        assertEquals(Map.of(), answer(memo, log, _ -> false));
    }

    @Test
    public void theMemoStaysUnderItsByteCap() throws IOException {
        final var capped = new IndexLogMemo(2048);
        final var logs = new java.util.ArrayList<File>();
        for (var k = 0; k < 6; k++) {
            final var other = new File(log.getParentFile(), COLL + "-f" + k + "-String.idxlog");
            write(other, added("key" + k, "x" + k) + added("other" + k, "y" + k, "z" + k));
            logs.add(other);
            assertEquals(Map.of("key" + k, Set.of("x" + k), "other" + k, Set.of("y" + k, "z" + k)),
                    answer(capped, other, EVERY));
            assertTrue(capped.bytes() <= 2048, "memo bytes " + capped.bytes());
        }
        assertTrue(capped.holds(logs.getLast()));
        assertFalse(capped.holds(logs.getFirst()), "the least recently used log goes first");

        final var huge = new StringBuilder();
        for (var k = 0; k < 200; k++) {
            huge.append(added("value" + k, "id" + k));
        }
        write(log, huge.toString());
        assertEquals(200, answer(capped, log, EVERY).size());
        assertFalse(capped.holds(log), "a log larger than the whole budget is parsed per lookup, never held");
        assertTrue(capped.bytes() <= 2048);
    }

    @Test
    public void aLogDeletedBetweenTwoQueriesIsAbsent() throws IOException {
        write(log, added("alpha", "a1"));
        assertTrue(memo.overlayOf(log, IndexKeyOrder.of(String.class), EVERY).present());

        Files.delete(log.toPath());

        assertFalse(memo.overlayOf(log, IndexKeyOrder.of(String.class), EVERY).present());
        assertFalse(memo.holds(log));
        write(log, added("beta", "b1") + added("gamma", "g1"));
        assertEquals(Map.of("beta", Set.of("b1"), "gamma", Set.of("g1")), answer());
    }

    @Test
    public void aShorterOrRewrittenLogIsParsedAgain() throws IOException {
        write(log, added("alpha", "a1") + added("beta", "b1"));
        assertEquals(Map.of("alpha", Set.of("a1"), "beta", Set.of("b1")), answer());

        write(log, added("gamma", "g1"));
        assertEquals(Map.of("gamma", Set.of("g1")), answer());

        write(log, added("delta", "d1") + added("epsilon", "e1"));
        assertEquals(Map.of("delta", Set.of("d1"), "epsilon", Set.of("e1")), answer(),
                "a longer log whose remembered tail moved is parsed from the start");
    }

    @Test
    public void anotherKeyOrderIsParsedAgain() throws IOException {
        write(log, added("7", "n1"));
        assertEquals(Map.of("7", Set.of("n1")), answer());

        final var numbers = memo.overlayOf(log, IndexKeyOrder.of(Number.class), EVERY);

        assertEquals(Set.of("7"), numbers.keys());
        assertEquals(Set.of("n1"), numbers.applied("7", null));
    }

    @Test
    public void aDroppedFolderIsForgotten() throws IOException {
        write(log, added("alpha", "a1"));
        answer(IndexLogMemo.SHARED, log, EVERY);
        answer();
        assertTrue(IndexLogMemo.SHARED.holds(log));

        FieldIndexLog.forgetUnder(tmp);
        memo.forgetUnder(tmp);

        assertFalse(IndexLogMemo.SHARED.holds(log));
        assertFalse(memo.holds(log));
        assertEquals(0L, memo.bytes());
    }
}
