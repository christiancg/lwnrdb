package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionTriggerStagingTest {
    private static final String TRIGGER = "t";
    private static final String DOC_ID = "tx-doc";
    private static final Configuration configuration = Configuration.getInstance();

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition(TRIGGER, new LinkedHashSet<>(Set.of(EventType.CREATED)), "recalc",
                        TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner")));
        for (final var run : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(run.getRunId(), run.getTriggerName());
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private UUID transactionSavingOneDocument() {
        final var clientId = clientTracker.registerForwardedClient("staging-" + UUID.randomUUID());
        TransactionOperationHelper.start(clientId);
        final var object = new JsonObject();
        object.addProperty("_id", DOC_ID);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(DOC_ID);
        assertEquals(OperationStatus.OK, TransactionOperationHelper
                .bufferSave(request, clientTracker.getActiveTransaction(clientId)).getStatus());
        return clientId;
    }

    private String transactionIdOf(UUID clientId) {
        return clientTracker.getActiveTransaction(clientId).getTransactionId().toString();
    }

    private static Answer<Object> recordingInto(List<String> calls) {
        return invocation -> {
            calls.add(invocation.getMethod().getName());
            return invocation.callRealMethod();
        };
    }

    private static List<String> recordIdsOfTheRun(String txId) {
        return TriggerRunLog.recordIdsFor(TriggerRunLog.deterministicRunId(txId, TRIGGER, EventType.CREATED, DOC_ID));
    }

    @Test
    public void test_trigger_runs_are_recorded_before_the_commit_marker_is_cleared() {
        final var clientId = transactionSavingOneDocument();
        final var calls = new CopyOnWriteArrayList<String>();

        try (var ignoredCommitLog = mockStatic(TxCommitLog.class, recordingInto(calls));
                var ignoredRunLog = mockStatic(TriggerRunLog.class, recordingInto(calls))) {
            assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());
        }

        final var recorded = calls.indexOf("recordDeterministic");
        assertTrue(recorded >= 0, "the commit recorded no trigger run: " + calls);
        assertTrue(recorded < calls.indexOf("clearLocalCommit"),
                "the run must be durable before the evidence that would replay the commit is gone: " + calls);
    }

    @Test
    public void test_a_committed_transaction_records_its_run_under_the_deterministic_id() {
        final var clientId = transactionSavingOneDocument();
        final var txId = transactionIdOf(clientId);

        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());

        assertEquals(1, recordIdsOfTheRun(txId).size());
    }

    @Test
    public void test_replay_after_a_kill_past_staging_records_each_run_once() throws Exception {
        final var clientId = transactionSavingOneDocument();
        final var txId = transactionIdOf(clientId);
        final var failuresLeft = new AtomicInteger(1);

        try (var ignored = mockStatic(AdminOperationHelper.class, invocation -> {
            if ("deleteTransactionOps".equals(invocation.getMethod().getName()) && failuresLeft.getAndDecrement() > 0) {
                throw new IOException("killed after staging");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(),
                    TransactionOperationHelper.commit(clientId).getErrorCode());
        }
        assertEquals(1, recordIdsOfTheRun(txId).size(), "the interrupted commit had already staged its run");

        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertEquals(1, recordIdsOfTheRun(txId).size(), "the replay must replace the staged run, not add one");
        assertEquals(1, TriggerRunLog.pending().size());
    }
}
