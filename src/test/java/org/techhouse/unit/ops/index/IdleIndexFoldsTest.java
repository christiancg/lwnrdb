package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.IndexLogChange;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.index.IdleIndexFolds;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IdleIndexFoldsTest {
    private static final String FIELD = "tag";
    private static final long QUIET = Globals.IDLE_INDEX_FOLD_QUIET_MS;

    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem realFs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final DueFoldFixture fixture = new DueFoldFixture();
    private Logger realLogger;
    private long markedFrom;
    private long markedBy;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        realLogger = TestUtils.getPrivateStaticField(IdleIndexFolds.class, "logger", Logger.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(IdleIndexFolds.class, "fs", realFs);
        TestUtils.setPrivateStaticField(IdleIndexFolds.class, "logger", realLogger);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void markDue(List<String> fields) throws Exception {
        final var marked = fixture.markDue(fields);
        markedFrom = marked.from();
        markedBy = marked.by();
    }

    private static File logOf(String field) {
        return DueFoldFixture.logOf(field);
    }

    private static String identifier() {
        return Cache.getIndexIdentifier(FIELD, String.class);
    }

    private static Map<Object, Set<String>> byValue(List<? extends FieldIndexEntry<?>> entries) {
        final var map = new TreeMap<Object, Set<String>>();
        entries.forEach(entry -> map.put(entry.getValue(), Set.copyOf(entry.getIds())));
        return map;
    }

    private static void waitPast(long millis) {
        while (System.currentTimeMillis() <= millis) {
            Thread.onSpinWait();
        }
    }

    @Test
    public void test_a_due_fold_runs_once_the_type_is_quiet() throws Exception {
        markDue(List.of(FIELD));

        assertEquals(0, IdleIndexFolds.runDue(markedFrom + QUIET - 1));
        assertTrue(logOf(FIELD).exists(), "a fold waits for the type to be quiet");
        assertEquals(1, IdleIndexFolds.runDue(markedBy + QUIET));

        assertFalse(logOf(FIELD).exists());
        final var resident = cache.residentFieldIndex(TestGlobals.DB, TestGlobals.COLL, identifier());
        assertNotNull(resident, "the folded type stays resident");
        assertEquals(byValue(realFs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class)),
                byValue(resident));
        assertEquals(0, IdleIndexFolds.runDue(markedBy + QUIET), "a folded type is no longer due");
    }

    @Test
    public void test_a_fold_marked_again_inside_the_quiet_window_waits() throws Exception {
        markDue(List.of(FIELD));
        waitPast(markedBy);

        fixture.saveRound(List.of(FIELD), 3);
        final var markedAgainBy = System.currentTimeMillis();

        assertEquals(0, IdleIndexFolds.runDue(markedBy + QUIET), "the second mark restarts the quiet window");
        assertTrue(logOf(FIELD).exists());
        assertEquals(1, IdleIndexFolds.runDue(markedAgainBy + QUIET));
        assertFalse(logOf(FIELD).exists());
    }

    @Test
    public void test_a_busy_index_lock_keeps_the_fold_due() throws Exception {
        markDue(List.of(FIELD));
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = Thread.ofPlatform().start(() -> {
            try {
                locks.lockIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
                held.countDown();
                release.await();
                locks.releaseIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(held.await(10, TimeUnit.SECONDS));

        final var whileHeld = IdleIndexFolds.runDue(markedBy + QUIET);
        release.countDown();
        holder.join(10_000);

        assertEquals(0, whileHeld, "a fold never waits for a busy index lock");
        assertTrue(logOf(FIELD).exists());
        assertEquals(1, IdleIndexFolds.runDue(markedBy + QUIET));
        assertFalse(logOf(FIELD).exists());
    }

    @Test
    public void test_an_evicted_type_is_forgotten_without_folding() throws Exception {
        markDue(List.of(FIELD));
        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);

        assertEquals(1, IdleIndexFolds.runDue(markedBy + QUIET));

        assertTrue(logOf(FIELD).exists(), "an evicted type's log is replayed by its next load");
        assertEquals(0, IdleIndexFolds.runDue(markedBy + QUIET), "the evicted type is forgotten");
    }

    @Test
    public void test_a_log_moved_since_the_fold_was_planned_is_skipped() throws Exception {
        markDue(List.of(FIELD));
        final var resident = cache.residentFieldIndex(TestGlobals.DB, TestGlobals.COLL, identifier());
        final var first = resident.getFirst();
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(IdleIndexFolds.class, "fs", spied);
        doAnswer(invocation -> {
            realFs.appendIndexLog(TestGlobals.DB, TestGlobals.COLL, FIELD, "String",
                    List.of(new IndexLogChange(FieldIndexEntry.fileKeyOf(first.getValue()), List.copyOf(first.getIds()),
                            List.of())),
                    resident, false);
            return invocation.callRealMethod();
        }).when(spied).fold(any());

        assertEquals(1, IdleIndexFolds.runDue(markedBy + QUIET));
        assertTrue(logOf(FIELD).exists(), "a fold planned before another append never drops it");

        TestUtils.setPrivateStaticField(IdleIndexFolds.class, "fs", realFs);
        assertEquals(1, IdleIndexFolds.runDue(markedBy + QUIET), "the skipped fold stays due");
        assertFalse(logOf(FIELD).exists());
    }

    @Test
    public void test_at_most_the_pass_limit_runs_per_call() throws Exception {
        final var fields = new ArrayList<String>();
        for (var i = 0; i <= Globals.IDLE_INDEX_FOLDS_PER_PASS; i++) {
            fields.add("f" + i);
        }
        markDue(fields);

        assertEquals(Globals.IDLE_INDEX_FOLDS_PER_PASS, IdleIndexFolds.runDue(markedBy + QUIET));
        assertEquals(1, fields.stream().filter(field -> logOf(field).exists()).count());
        assertEquals(1, IdleIndexFolds.runDue(markedBy + QUIET));
        assertTrue(fields.stream().noneMatch(field -> logOf(field).exists()));
    }

    @Test
    public void test_dropping_the_collection_or_database_forgets_its_due_folds() throws Exception {
        markDue(List.of(FIELD));
        IdleIndexFolds.forgetCollection(TestGlobals.DB, "otherColl");
        IdleIndexFolds.forgetDatabase("otherDb");

        IdleIndexFolds.forgetCollection(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(0, IdleIndexFolds.runDue(markedBy + QUIET));

        fixture.saveRound(List.of(FIELD), 3);
        IdleIndexFolds.forgetDatabase(TestGlobals.DB);
        assertEquals(0, IdleIndexFolds.runDue(System.currentTimeMillis() + QUIET));
        assertTrue(logOf(FIELD).exists());
    }

    @Test
    public void test_a_failed_fold_stays_due_and_logs_a_warning() throws Exception {
        markDue(List.of(FIELD));
        final var spied = spy(realFs);
        doThrow(new IOException("disk full")).when(spied).fold(any());
        final var logger = mock(Logger.class);
        TestUtils.setPrivateStaticField(IdleIndexFolds.class, "fs", spied);
        TestUtils.setPrivateStaticField(IdleIndexFolds.class, "logger", logger);
        final var failedAt = markedBy + QUIET;

        assertEquals(1, IdleIndexFolds.runDue(failedAt));

        verify(logger).warning(argThat((String line) -> line.contains("disk full") && line.contains(FIELD)));
        assertTrue(logOf(FIELD).exists());
        TestUtils.setPrivateStaticField(IdleIndexFolds.class, "fs", realFs);
        assertEquals(0, IdleIndexFolds.runDue(failedAt + QUIET), "a failed fold backs off before it retries");
        assertEquals(1, IdleIndexFolds.runDue(failedAt + 2 * QUIET));
        assertFalse(logOf(FIELD).exists());
    }
}
