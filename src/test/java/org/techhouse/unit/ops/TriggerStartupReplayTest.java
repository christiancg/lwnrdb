package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.ProcedureDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.tx.CommittedOpTriggers;
import org.techhouse.ops.tx.TransactionRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerStartupReplayTest {
    private static final Configuration configuration = Configuration.getInstance();
    private static final String COORDINATOR = "127.0.0.1:9000";

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final CopyOnWriteArrayList<TriggerEvent> captured = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(TriggerExecutor.class).stop();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        triggerExecutor.stop();
        triggerExecutor.start(captured::add);
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        captured.clear();
        final var definition = new ProcedureDefinition("noop", "export default 1;", 1L, null, true, 1L, 1L, "admin");
        fs.writeProcedure(TestGlobals.DB, "noop", eJson.toJson(definition.toJsonObject()));
        cache.putProcedure(TestGlobals.DB, definition);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit",
                        new LinkedHashSet<>(Set.of(EventType.CREATED, EventType.UPDATED)), "noop",
                        TriggerDefinition.MODE_DOCUMENT, false, true, "admin", 1L, 1L, 1L, "admin")));
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("value", new JsonString("v"));
        return object;
    }

    private static List<String> collections() {
        return List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL));
    }

    private String crashAfterStagingCommittedSlice(String id, boolean prepared) throws Exception {
        final var clientId = clientTracker.registerForwardedClient("admin");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id));
        request.set_id(id);
        TransactionOperationHelper.bufferSave(request, transaction);
        final var txId = transaction.getTransactionId().toString();
        if (prepared) {
            Tx2pcLog.recordParticipantPrepared(txId, COORDINATOR, List.of(COORDINATOR), collections());
        }
        final var ops = AdminOperationHelper.readTransactionOps(transaction.getBufferedOpIds());
        ops.sort(Comparator.comparingLong(AdminTransactionEntry::getSeq));
        assertTrue(TransactionRecovery.applyAllWithRetry(ops, txId), "the slice's ops must apply");
        CommittedOpTriggers.stage(ops, "admin", 0, transaction, txId);
        if (!prepared) {
            TransactionRecovery.discardAppliedOps(txId, transaction.getBufferedOpIds());
        }
        clientTracker.removeById(clientId);
        TestUtils.releaseAllLocks();
        return txId;
    }

    private Set<String> pendingRunIds() throws Exception {
        final var runIds = new HashSet<String>();
        for (final var entry : TriggerRunLog.pending()) {
            runIds.add(entry.getRunId());
        }
        return runIds;
    }

    private List<String> queuedRunIds() {
        letTheExecutorSettle();
        return captured.stream().map(TriggerEvent::getRunId).toList();
    }

    private static void letTheExecutorSettle() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void test_run_of_a_prepared_slice_is_left_to_its_replay() throws Exception {
        final var txId = crashAfterStagingCommittedSlice("prepared-replayed", true);
        final var staged = pendingRunIds();
        assertFalse(staged.isEmpty(), "the crash must leave the staged run on disk");

        final var startupRuns = TriggerRunRecovery.startupRunIds();
        assertTrue(startupRuns.stream().noneMatch(staged::contains),
                "a run of a still-prepared slice belongs to its replay, not to startup recovery");

        TwoPhaseParticipant.commitPreparedFromDurable(txId, collections());
        TriggerRunRecovery.recoverLocal(startupRuns);

        final var queued = queuedRunIds();
        assertEquals(staged.size(), queued.size(), "each staged run must be queued exactly once: " + queued);
        assertEquals(staged, new HashSet<>(queued));
    }

    @Test
    public void test_run_of_an_unresolved_prepared_slice_is_not_run_by_startup_recovery() throws Exception {
        final var txId = crashAfterStagingCommittedSlice("prepared-unresolved", true);
        final var staged = pendingRunIds();

        TriggerRunRecovery.recoverLocal(TriggerRunRecovery.startupRunIds());
        assertTrue(queuedRunIds().isEmpty(), "startup recovery must leave an unresolved slice's run to its replay");

        TwoPhaseParticipant.commitPreparedFromDurable(txId, collections());
        assertEquals(staged, new HashSet<>(queuedRunIds()), "the replay must queue the run once");
        assertEquals(staged.size(), captured.size());
    }

    @Test
    public void test_run_of_a_finished_local_commit_is_recovered_at_startup() throws Exception {
        crashAfterStagingCommittedSlice("finished-commit", false);
        final var staged = pendingRunIds();
        assertFalse(staged.isEmpty());

        final var startupRuns = TriggerRunRecovery.startupRunIds();
        assertTrue(startupRuns.containsAll(staged), "a run whose transaction holds no marker is recovery's to replay");

        TriggerRunRecovery.recoverLocal(startupRuns);
        assertEquals(staged, new HashSet<>(queuedRunIds()));
        assertEquals(staged.size(), captured.size());
    }

    @Test
    public void test_record_without_tx_id_is_recovered_as_before() throws Exception {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("standalone"));
        request.set_id("standalone");
        processor.processMessage(request);
        final var standaloneRuns = pendingRunIds();
        assertFalse(standaloneRuns.isEmpty(), "a standalone save must leave its run pending");
        assertTrue(TriggerRunLog.pending().stream().map(AdminTriggerRunEntry::getTxId).allMatch(Objects::isNull),
                "a standalone run carries no transaction id");

        assertTrue(TriggerRunRecovery.startupRunIds().containsAll(standaloneRuns));
    }

    @Test
    public void test_staged_tx_id_is_the_transaction_that_produced_the_run() throws Exception {
        final var txId = crashAfterStagingCommittedSlice("stamped", true);

        assertTrue(TriggerRunLog.pending().stream().allMatch(entry -> txId.equals(entry.getTxId())),
                "every chunk of a deterministic run must name its transaction");
        Tx2pcLog.deleteParticipantMarker(txId);
    }

    @Test
    public void test_startup_run_ids_falls_back_to_every_pending_run_when_records_cannot_be_read() throws Exception {
        final var txId = crashAfterStagingCommittedSlice("unreadable", true);
        final var everyPending = TriggerRunLog.pendingRunIds();

        try (var ignored = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            ignored.when(() -> AdminOperationHelper.readTriggerRuns(anyList())).thenThrow(new IOException("torn"));

            assertEquals(everyPending, TriggerRunRecovery.startupRunIds());
        }
        Tx2pcLog.deleteParticipantMarker(txId);
    }

    @Test
    public void test_unknown_transaction_id_is_not_fenced() {
        assertFalse(TransactionOperationHelper.isFenced(UUID.randomUUID().toString()));
    }
}
