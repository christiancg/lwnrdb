package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ScriptRunHistory;
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRecord;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScriptRunHistoryRetryTest {
    private static final String OWNER = "127.0.0.1:59996";
    private static final long WAIT_MILLIS = 15_000L;
    private final Configuration configuration = Configuration.getInstance();
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final ScriptRunHistory history = IocContainer.get(ScriptRunHistory.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final EJson eJson = IocContainer.get(EJson.class);
    private final AtomicInteger forwards = new AtomicInteger();
    private PeerConnectionPool routerPool;
    private OwnershipManager routerOwnership;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "scriptRunHistoryEnabled", true);
        ScriptRunHistory.write(row("run-creates-the-collection"));
        ScriptRunHistory.reset();
        routerPool = TestUtils.getPrivateField(router, "pool", PeerConnectionPool.class);
        routerOwnership = TestUtils.getPrivateField(router, "ownershipManager", OwnershipManager.class);
        cluster.enableAlone();
        final var ownership = mock(OwnershipManager.class);
        when(ownership.isOwner(anyString(), anyString())).thenReturn(false);
        when(ownership.ownerAddress(anyString(), anyString())).thenReturn(OWNER);
        TestUtils.setPrivateField(router, "ownershipManager", ownership);
        TestUtils.setPrivateField(configuration, "backgroundProcessingThreads", 2);
        taskManager.stopBackgroundWorkers();
        taskManager.startBackgroundWorkers();
        history.startSweep();
    }

    @AfterEach
    public void tearDown() throws Exception {
        history.stopSweep();
        taskManager.stopBackgroundWorkers();
        TestUtils.setPrivateField(configuration, "backgroundProcessingThreads",
                ConfigKey.BACKGROUND_PROCESSING_THREADS.defaultValue());
        TestUtils.setPrivateField(router, "pool", routerPool);
        TestUtils.setPrivateField(router, "ownershipManager", routerOwnership);
        cluster.disable();
        TestUtils.setPrivateField(configuration, "scriptRunHistoryEnabled", false);
        ScriptRunHistory.reset();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_a_row_the_owner_has_not_caught_up_with_is_retried_until_it_lands() throws Exception {
        ownerAnswers(List.of(notReady(), saved()));
        ScriptRunHistory.write(row("run-retried"));
        assertTrue(eventually(() -> ScriptRunHistory.getRecorded() == 1L), "the retried row never landed");
        assertEquals(0L, ScriptRunHistory.getDropped());
        assertEquals(2, forwards.get());
    }

    @Test
    public void test_a_row_the_owner_never_catches_up_with_is_dropped_after_the_retries() throws Exception {
        ownerAnswers(List.of(notReady()));
        ScriptRunHistory.write(row("run-never-ready"));
        assertTrue(eventually(() -> ScriptRunHistory.getDropped() == 1L), "the row was never given up on");
        assertEquals(0L, ScriptRunHistory.getRecorded());
        assertEquals(6, forwards.get());
    }

    @Test
    public void test_without_the_sweeper_a_not_ready_row_is_dropped_at_once() throws Exception {
        history.stopSweep();
        ownerAnswers(List.of(notReady()));
        ScriptRunHistory.write(row("run-no-scheduler"));
        assertEquals(1L, ScriptRunHistory.getDropped());
        assertEquals(1, forwards.get());
    }

    private void ownerAnswers(List<String> answers) throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(_ -> {
            final var index = forwards.getAndIncrement();
            final var reply = new ClusterMessage();
            reply.setType(ClusterMessageType.FORWARD_RESPONSE);
            reply.setForwardBody(ForwardBody.encode(answers.get(Math.min(index, answers.size() - 1))));
            return reply;
        });
        TestUtils.setPrivateField(router, "pool", pool);
    }

    private String notReady() {
        return eJson.toJson(new OperationResponse(OperationType.SAVE, ErrorCode.COLLECTION_NOT_READY));
    }

    private String saved() {
        return eJson.toJson(OperationResponse.ok(OperationType.SAVE, "Successfully saved"));
    }

    @SuppressWarnings("BusyWait")
    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        final var deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static ScriptRunRecord row(String runId) {
        return new ScriptRunRecord(runId, ScriptRunKind.CALL_PROCEDURE, TestGlobals.DB, null, "proc", null, null,
                "admin", "admin", System.currentTimeMillis(), 1L, 1, ScriptRunRecord.OUTCOME_OK, null, null, null, null,
                null, false);
    }
}
