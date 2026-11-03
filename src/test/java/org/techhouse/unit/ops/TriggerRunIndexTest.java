package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.AdminCache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TriggerRunIndex;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class TriggerRunIndexTest {
    private static final long SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(Configuration.getInstance(), "triggerRunLogEnabled", true);
        TriggerTestSupport.consumeAllPendingRuns();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static DbEntry document(String id) {
        final var data = new JsonObject();
        data.add(Globals.PK_FIELD, new JsonString(id));
        return DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
    }

    private static TriggerRunLog.TriggerRunDescriptor descriptor(List<DbEntry> entries) {
        return new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit", "recalc",
                EventType.CREATED, false, "alice", 0, System.currentTimeMillis(), entries);
    }

    static AdminTriggerRunEntry run(String runId, String nodeId, String dbName, long firedAt, TriggerRunStatus status) {
        final var entry = new AdminTriggerRunEntry(runId, 0, nodeId, dbName, TestGlobals.COLL, "audit", "recalc",
                EventType.CREATED, false, "alice", 0, firedAt, List.of("a"), List.of());
        entry.markAttempt(status, status == TriggerRunStatus.DEAD ? 3 : 0, null, 0L);
        return entry;
    }

    private static List<String> describe(TriggerRunIndex.Summary run) {
        return List.of(run.recordId(), run.runId(), String.valueOf(run.nodeId()), run.status().name(),
                String.valueOf(run.firedAt()), String.valueOf(run.lastErrorAt()), String.valueOf(run.dbName()),
                String.valueOf(run.collName()), String.valueOf(run.txId()));
    }

    private static List<String> describe(AdminTriggerRunEntry entry) {
        return List.of(entry.get_id(), entry.getRunId(), String.valueOf(entry.getNodeId()), entry.getStatus().name(),
                String.valueOf(entry.getFiredAt()), String.valueOf(entry.getLastErrorAt()),
                String.valueOf(entry.getDbName()), String.valueOf(entry.getCollName()),
                String.valueOf(entry.getTxId()));
    }

    private static Map<String, List<String>> streamedRows() throws Exception {
        final var rows = new HashMap<String, List<String>>();
        for (final var entry : TriggerRunLog.pending()) {
            rows.put(entry.get_id(), describe(entry));
        }
        return rows;
    }

    private static Map<String, List<String>> indexedWithoutReading() throws Exception {
        final var indexed = new HashMap<String, List<String>>();
        try (var admin = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            for (final var run : TriggerRunIndex.current()) {
                indexed.put(run.recordId(), describe(run));
            }
            admin.verify(() -> AdminOperationHelper.readTriggerRuns(anyList()), never());
        }
        return indexed;
    }

    private static List<DbEntry> documents() {
        final var documents = new ArrayList<DbEntry>();
        for (var index = 0; index < 20_000; index++) {
            documents.add(document("an-id-long-enough-to-fill-a-chunk-quickly-" + index));
        }
        return documents;
    }

    @Test
    public void indexMatchesStreamedRows() throws Exception {
        assertNotNull(TriggerRunLog.record(descriptor(List.of(document("a")))));
        final var staged = new ArrayList<AdminTriggerRunEntry>();
        assertNotNull(TriggerRunLog.recordStaged(descriptor(documents()), Map.of(), staged::add));
        AdminOperationHelper.saveTriggerRuns(staged);
        final var retried = TriggerRunLog.record(descriptor(List.of(document("b"))));
        TriggerRunLog.markAttempt(retried, TriggerRunStatus.PENDING, 2, "boom", System.currentTimeMillis());
        final var dead = TriggerRunLog.record(descriptor(List.of(document("c"))));
        TriggerRunLog.markAttempt(dead, TriggerRunStatus.DEAD, 3, "boom", 0L);
        TriggerRunLog.discard(TriggerRunLog.record(descriptor(List.of(document("d")))));
        final var deterministic = TriggerRunLog.deterministicRunId("tx-1", TestGlobals.DB, TestGlobals.COLL, "audit",
                EventType.CREATED, null);
        assertNotNull(TriggerRunLog.recordDeterministic(descriptor(List.of(document("e"))), "tx-1", deterministic));
        final var replayed = new ArrayList<AdminTriggerRunEntry>();
        assertNotNull(TriggerRunLog.recordStaged(descriptor(List.of(document("e"))), Map.of("e", 5L), replayed::add));
        AdminOperationHelper.saveTriggerRuns(replayed, TriggerRunLog.recordIdsFor(deterministic));
        final var narrowed = TriggerRunLog.recordStaged(descriptor(documents()), Map.of());
        TriggerRunLog.confirmStaged(narrowed, Set.of("an-id-long-enough-to-fill-a-chunk-quickly-1"));

        final var indexed = indexedWithoutReading();

        final var rows = streamedRows();
        assertTrue(rows.size() > 5, "several runs and chunks are on disk");
        assertEquals(rows, indexed);
    }

    @Test
    public void sweepFetchesOnlyActedRows() throws Exception {
        final var now = System.currentTimeMillis();
        final var runs = new ArrayList<AdminTriggerRunEntry>();
        final var stranded = new ArrayList<String>();
        for (var index = 0; index < 30; index++) {
            runs.add(run("own-" + index, TriggerRunLog.currentNodeId(), TestGlobals.DB, now, TriggerRunStatus.PENDING));
            runs.add(run("dead-" + index, TriggerRunLog.currentNodeId(), TestGlobals.DB, now, TriggerRunStatus.DEAD));
        }
        for (var index = 0; index < 5; index++) {
            final var foreign = run("foreign-" + index, "another-node", TestGlobals.DB, now - SEVEN_DAYS_MS,
                    TriggerRunStatus.PENDING);
            runs.add(foreign);
            stranded.add(foreign.get_id());
        }
        AdminOperationHelper.saveTriggerRuns(runs);

        try (var admin = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            TriggerRunRecovery.warnAboutStrandedRuns();
            TriggerRunLog.garbageCollect(60_000L, SEVEN_DAYS_MS);

            admin.verify(() -> AdminOperationHelper.readTriggerRuns(anyList()), never());
            admin.verify(() -> AdminOperationHelper.deleteTriggerRuns(new ArrayList<>(stranded)));
        }
        assertEquals(60, TriggerRunLog.pending().size());
    }

    @Test
    public void startupBuildsIndexInChunks() throws Exception {
        final var runs = new ArrayList<AdminTriggerRunEntry>();
        final var expected = new HashSet<String>();
        for (var index = 0; index < 2_500; index++) {
            runs.add(run("run-" + index, TriggerRunLog.currentNodeId(), TestGlobals.DB, System.currentTimeMillis(),
                    TriggerRunStatus.PENDING));
            expected.add("run-" + index);
        }
        runs.add(run("dead", TriggerRunLog.currentNodeId(), TestGlobals.DB, System.currentTimeMillis(),
                TriggerRunStatus.DEAD));
        AdminOperationHelper.saveTriggerRuns(runs);
        reloadTriggerRunRowsFromDisk();

        final var chunkSizes = new ArrayList<Integer>();
        try (var admin = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            admin.when(() -> AdminOperationHelper.readTriggerRuns(anyList())).thenAnswer(call -> {
                final List<String> ids = call.getArgument(0);
                chunkSizes.add(ids.size());
                return call.callRealMethod();
            });

            assertEquals(expected, TriggerRunRecovery.startupRunIds());
        }

        assertEquals(List.of(1024, 1024, 453), chunkSizes);
        assertEquals(streamedRows(), indexedWithoutReading());
    }

    private static void reloadTriggerRunRowsFromDisk() throws Exception {
        final var reloaded = new ConcurrentSkipListMap<String, PkIndexEntry>();
        for (final var row : IocContainer.get(FileSystem.class).readWholePkIndexFile(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME)) {
            reloaded.put(row.getValue(), row);
        }
        TestUtils.setPrivateField(IocContainer.get(AdminCache.class), "triggerRunsPkIndex", reloaded);
    }
}
