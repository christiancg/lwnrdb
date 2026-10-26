package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.Tx2pcCoordinator;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.admin.PageRowWriteBack;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionForceCountTest {
    private static final String COORDINATOR = "127.0.0.1:9999";
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private RecordingForcer recorder;
    private int sequence;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        recorder = RecordingForcer.install(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        PageRowWriteBack.flushAll();
        recorder.close();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private int forces() {
        return recorder.forcedNames().size();
    }

    private UUID openTransactionWithOneSave() {
        final var clientId = clientTracker.registerForwardedClient("forces");
        TestUtils.ensureAdminUser(clientTracker.getAuthenticatedUsername(clientId));
        TransactionOperationHelper.start(clientId);
        bufferOneSave(clientId);
        return clientId;
    }

    private void bufferOneSave(UUID clientId) {
        final var id = "forced-" + sequence++;
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        assertEquals(OperationStatus.OK, TransactionOperationHelper
                .bufferSave(request, clientTracker.getActiveTransaction(clientId)).getStatus());
    }

    private void warmUp() {
        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(openTransactionWithOneSave()).getStatus());
        recorder.clear();
    }

    @Test
    public void test_buffering_and_rolling_back_force_nothing() {
        warmUp();
        final var clientId = openTransactionWithOneSave();
        bufferOneSave(clientId);
        assertEquals(0, forces(), "buffering is in memory: " + recorder.forcedNames());

        assertEquals(OperationStatus.OK, TransactionOperationHelper.rollback(clientId).getStatus());

        assertEquals(0, forces(), "nothing reached the disk, so nothing is undone: " + recorder.forcedNames());
    }

    @Test
    public void test_a_one_save_commit_forces_three_times() {
        warmUp();
        final var clientId = openTransactionWithOneSave();

        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());

        assertEquals(3, forces(), "begin record, the apply and the finish record: " + recorder.forcedNames());
    }

    @Test
    public void test_a_participant_forces_four_times() {
        warmUp();
        final var clientId = openTransactionWithOneSave();

        assertTrue(TwoPhaseParticipant.prepare(clientId, COORDINATOR, List.of(COORDINATOR)));
        assertEquals(OperationStatus.OK, TwoPhaseParticipant.commitPrepared(clientId).getStatus());

        assertEquals(4, forces(), "prepare, begin, the apply and the finish record: " + recorder.forcedNames());
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_a_coordinator_with_a_local_slice_forces_five_times() throws Exception {
        final var cluster = new ClusterTestHarness();
        cluster.enableAlone();
        try {
            final var coordinator = IocContainer.get(Tx2pcCoordinator.class);
            warmUp();
            final var clientId = clientTracker.addClient(TestUtils.clientSocket());
            processor.processMessage(new StartTransactionRequest(), clientId);
            final var object = new JsonObject();
            object.add(Globals.PK_FIELD, new JsonString("coordinated"));
            final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObject(object);
            request.set_id("coordinated");
            processor.processMessage(request, clientId);
            clientTracker.markLocalSlice(clientId);
            recorder.clear();

            assertEquals(OperationStatus.OK, coordinator.commit(clientId).getStatus());

            assertEquals(5, forces(), "prepare, the decision with begin, the apply, the finish record and the"
                    + " decision's deletion: " + recorder.forcedNames());
        } finally {
            cluster.disable();
        }
    }
}
