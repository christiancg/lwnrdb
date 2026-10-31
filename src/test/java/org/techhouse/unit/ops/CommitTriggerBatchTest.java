package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.DbEntry;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TriggerHelper;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.WriteEffects;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class CommitTriggerBatchTest {
    private static final String TRIGGER = "t";
    private static final int BATCH = 40;
    private static final String TX_ID = "batch-tx";
    private static final String RUNS_LOG = Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME + Globals.WAL_EXTENSION;
    private static final Configuration configuration = Configuration.getInstance();

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(TriggerExecutor.class).stop();
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition(TRIGGER, new LinkedHashSet<>(Set.of(EventType.CREATED)), "recalc",
                        TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner")));
        TriggerTestSupport.consumeAllPendingRuns();
    }

    @AfterEach
    public void tearDown() throws Exception {
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private UUID transactionSavingTheBatch() {
        final var clientId = clientTracker.registerForwardedClient("batch-" + UUID.randomUUID());
        TestUtils.ensureAdminUser(clientTracker.getAuthenticatedUsername(clientId));
        TransactionOperationHelper.start(clientId);
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < BATCH; i++) {
            final var object = new JsonObject();
            object.addProperty(Globals.PK_FIELD, "tx-batch-" + i);
            objects.add(object);
        }
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(objects);
        assertEquals(OperationStatus.OK, TransactionOperationHelper
                .bufferBulkSave(request, clientTracker.getActiveTransaction(clientId)).getStatus());
        return clientId;
    }

    private static DbEntry entryOf(String id) {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        return DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
    }

    private static String runIdOf(String id) {
        return TriggerRunLog.deterministicRunId(TX_ID, TestGlobals.DB, TestGlobals.COLL, TRIGGER, EventType.CREATED,
                id);
    }

    private static void writeStaleChunk(String id) throws Exception {
        AdminOperationHelper.saveTriggerRun(new AdminTriggerRunEntry(runIdOf(id), 1, TriggerRunLog.currentNodeId(),
                TestGlobals.DB, TestGlobals.COLL, TRIGGER, "recalc", EventType.CREATED, false, "owner", 0,
                System.currentTimeMillis(), List.of("stale-" + id), List.of()));
    }

    @Test
    public void test_a_commit_of_many_triggered_saves_stages_every_run_in_one_force() throws Exception {
        final var clientId = transactionSavingTheBatch();

        try (var recorder = RecordingForcer.install(true)) {
            assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());

            assertEquals(1L, recorder.forcedNames().stream().filter(RUNS_LOG::equals).count(),
                    "forty runs of one commit must be staged by one forced record");
        }
        final var records = TriggerRunLog.pending();
        assertEquals(40, records.size());
        assertEquals(40, records.stream().map(AdminTriggerRunEntry::getRunId).distinct().count());
    }

    @Test
    public void test_a_restaged_run_replaces_its_stale_chunks_in_the_same_record() throws Exception {
        writeStaleChunk("restaged");

        try (var admin = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            final var batch = WriteEffects.batched();
            TriggerHelper.stageCommitted(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                    List.of(entryOf("restaged")), "owner", 0, TX_ID, batch);
            batch.flushStaging(TX_ID);

            admin.verify(() -> AdminOperationHelper.deleteTriggerRuns(anyList()), never());
        }
        assertEquals(List.of(AdminTriggerRunEntry.buildId(runIdOf("restaged"), 0)),
                TriggerRunLog.recordIdsFor(runIdOf("restaged")));
        assertEquals(List.of("restaged"), TriggerRunLog.pending().getFirst().getIds());
    }

    @Test
    public void test_a_failed_batch_falls_back_to_rewriting_each_run() throws Exception {
        writeStaleChunk("fallback-a");

        try (var admin = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            admin.when(() -> AdminOperationHelper.saveTriggerRuns(anyList(), anyList()))
                    .thenThrow(new IOException("disk full"));
            final var batch = WriteEffects.batched();
            final var staged = TriggerHelper.stageCommitted(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                    List.of(entryOf("fallback-a"), entryOf("fallback-b")), "owner", 0, TX_ID, batch);
            batch.flushStaging(TX_ID);
            assertFalse(staged.isEmpty());
        }
        assertEquals(List.of(AdminTriggerRunEntry.buildId(runIdOf("fallback-a"), 0)),
                TriggerRunLog.recordIdsFor(runIdOf("fallback-a")), "the fallback discards the stale chunk too");
        assertEquals(List.of("fallback-a", "fallback-b"),
                TriggerRunLog.pending().stream().flatMap(record -> record.getIds().stream()).sorted().toList());
    }
}
