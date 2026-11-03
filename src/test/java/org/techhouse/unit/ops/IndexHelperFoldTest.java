package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.IndexLogChange;
import org.techhouse.fs.PendingFold;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.index.DeferredFold;
import org.techhouse.ops.index.FieldIndexFolds;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexHelperFoldTest {
    private static final String FIELD = "tag";
    private static final String SLOT_CLASS = "org.techhouse.ops.index.FieldIndexSlot";
    private static final int IDS = 12;
    private static final String PADDING = "p".repeat(2000);
    private static final int BASE_ENTRIES = 5000;
    private static final String KEY_PADDING = "k".repeat(200);

    private final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem realFs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private Long savedMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(FieldIndexFolds.class, "fs", realFs);
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", realFs);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private List<String> ids() {
        final var ids = new ArrayList<String>(IDS);
        for (var i = 0; i < IDS; i++) {
            ids.add("id" + i);
        }
        return ids;
    }

    private void saveRound(int round) throws Exception {
        final var objects = new ArrayList<JsonObject>(IDS);
        for (final var id : ids()) {
            final var doc = new JsonObject();
            doc.add(Globals.PK_FIELD, new JsonString(id));
            doc.add(FIELD, new JsonString(PADDING + round + "-" + id));
            objects.add(doc);
        }
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(objects);
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            SaveOperationHelper.executeBulkSave(request);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
        TestUtils.discardQueuedBackgroundEvents(taskManager);
    }

    private void indexTheField() throws Exception {
        saveRound(0);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(FIELD));
    }

    private static File logOf(String field) {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL,
                TestGlobals.COLL + "-" + field + "-String" + Globals.INDEX_LOG_FILE_EXTENSION);
    }

    private record Parked(boolean collectionHeld, boolean indexHeld, boolean writerGotIn) {
    }

    @Test
    public void foldRunsAfterCollectionLockReleased() throws Exception {
        indexTheField();
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(FieldIndexFolds.class, "fs", spied);
        final var parked = new CopyOnWriteArrayList<Parked>();
        doAnswer(invocation -> {
            final var collectionHeld = locks.holdsCollectionLock(TestGlobals.DB, TestGlobals.COLL);
            final var indexHeld = locks.holdsIndexLock(TestGlobals.DB, TestGlobals.COLL, FIELD);
            final var writerGotIn = CompletableFuture.supplyAsync(() -> {
                try {
                    final var got = locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL, 2_000L);
                    if (got) {
                        locks.release(TestGlobals.DB, TestGlobals.COLL);
                    }
                    return got;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }).get();
            parked.add(new Parked(collectionHeld, indexHeld, writerGotIn));
            return invocation.callRealMethod();
        }).when(spied).fold(any());

        for (var round = 1; round <= 6 && parked.isEmpty(); round++) {
            saveRound(round);
            IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, ids());
        }

        assertEquals(1, parked.size(), "the log outgrew its threshold once");
        assertFalse(parked.getFirst().collectionHeld(), "the fold runs after the collection lock is released");
        assertTrue(parked.getFirst().indexHeld(), "the fold holds the field's index write lock");
        assertTrue(parked.getFirst().writerGotIn(), "a writer acquires the collection while the fold is parked");
        assertFalse(logOf(FIELD).exists(), "the fold landed");
        final var indexed = new HashSet<String>();
        for (final var entry : realFs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class)) {
            assertEquals(1, entry.getIds().size());
            assertTrue(entry.getValue().endsWith("-" + entry.getIds().iterator().next()));
            indexed.addAll(entry.getIds());
        }
        assertEquals(Set.copyOf(ids()), indexed);
    }

    private static List<FieldIndexEntry<?>> baseEntries(String suffix) {
        final var entries = new ArrayList<FieldIndexEntry<?>>(BASE_ENTRIES);
        for (var i = 0; i < BASE_ENTRIES; i++) {
            entries.add(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL,
                    String.format("a%05d%s%s", i, KEY_PADDING, suffix), new HashSet<>(Set.of("b" + i))));
        }
        return entries;
    }

    private static List<IndexLogChange> addedKeys(int count) {
        final var changes = new ArrayList<IndexLogChange>(count);
        for (var i = 0; i < count; i++) {
            changes.add(new IndexLogChange(String.format("z%05d%s", i, KEY_PADDING), List.of("n" + i), List.of()));
        }
        return changes;
    }

    private PendingFold appendBetweenAnEighthAndAllOfTheBase(String field, boolean refused) throws Exception {
        final var base = baseEntries(field);
        realFs.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, field, Map.of(String.class, base));
        return realFs.appendIndexLog(TestGlobals.DB, TestGlobals.COLL, field, "String", addedKeys(BASE_ENTRIES / 5),
                base, refused);
    }

    @Test
    public void refusedTypeFoldsAtEighthOfIdx() throws Exception {
        final var refused = appendBetweenAnEighthAndAllOfTheBase("refused", true);
        final var cached = appendBetweenAnEighthAndAllOfTheBase("cached", false);

        assertNotNull(refused, "a refused type folds once its log reaches an eighth of its .idx");
        assertNull(cached, "a cacheable type keeps logging until its log reaches its .idx");
        assertEquals(PendingFold.Outcome.FOLDED, realFs.fold(refused));
        assertFalse(logOf("refused").exists());
        assertTrue(logOf("cached").exists());
    }

    @Test
    public void aRefusedTypeAppendsWithTheRefusedThreshold() throws Exception {
        indexTheField();
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", Globals.CACHE_DISABLED);
        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", spied);
        saveRound(1);

        IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, ids());

        verify(spied, atLeastOnce()).appendIndexLog(anyString(), anyString(), eq(FIELD), eq("String"), any(), any(),
                eq(true));
    }

    @Test
    public void aFoldIsSkippedOnceAnotherAppendMovedTheLogOn() throws Exception {
        final var first = realFs.appendIndexLog(TestGlobals.DB, TestGlobals.COLL, FIELD, "String",
                addedKeys(BASE_ENTRIES / 5), List.of(baseEntries("").getFirst()), false);
        final var second = realFs.appendIndexLog(TestGlobals.DB, TestGlobals.COLL, FIELD, "String", addedKeys(1),
                List.of(baseEntries("").getFirst()), false);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(PendingFold.Outcome.SKIPPED, realFs.fold(first));
        assertTrue(logOf(FIELD).exists(), "a stale fold must not drop the later append");
        assertEquals(PendingFold.Outcome.FOLDED, realFs.fold(second));
        assertFalse(logOf(FIELD).exists());
    }

    @Test
    public void aFoldWhoseSourceCannotBeReadKeepsTheLog() throws Exception {
        final var pending = realFs.appendIndexLogOnly(TestGlobals.DB, TestGlobals.COLL, FIELD, "String",
                addedKeys(BASE_ENTRIES / 5), () -> null);
        assertNotNull(pending);

        FieldIndexFolds
                .runAfterUnlock(List.of(new DeferredFold(Cache.getIndexIdentifier(FIELD, String.class), pending)));

        assertTrue(logOf(FIELD).exists(), "the changes stay logged for the next append to fold");
        assertFalse(locks.holdsIndexLock(TestGlobals.DB, TestGlobals.COLL, FIELD));
    }
}
