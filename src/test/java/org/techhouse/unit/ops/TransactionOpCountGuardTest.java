package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionOpCountGuardTest {
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.resetClients();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        return obj;
    }

    private void bufferSave(java.util.UUID clientId, String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id));
        request.set_id(id);
        assertEquals(OperationStatus.OK, TransactionOperationHelper
                .bufferSave(request, clientTracker.getActiveTransaction(clientId)).getStatus());
    }

    private OperationStatus findStatus(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    @Test
    public void test_an_open_session_reports_undecided_rather_than_no_record() throws Exception {
        final var clientId = clientTracker.registerForwardedClient("in-flight");
        TransactionOperationHelper.start(clientId);
        final var dtxId = clientTracker.getActiveTransaction(clientId).getTransactionId().toString();

        assertEquals(Tx2pcLog.Status.UNKNOWN, Tx2pcLog.status(dtxId));

        TransactionOperationHelper.rollback(clientId);
        assertEquals(Tx2pcLog.Status.NO_RECORD, Tx2pcLog.status(dtxId));
    }

    @Test
    public void test_commit_fails_when_buffered_ops_are_missing() throws Exception {
        final var clientId = clientTracker.registerForwardedClient("op-count");
        TransactionOperationHelper.start(clientId);
        bufferSave(clientId, "kept");
        bufferSave(clientId, "destroyed");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        AdminOperationHelper.deleteTransactionOps(List.of(transaction.getBufferedOpIds().getLast()));

        final var response = TwoPhaseParticipant.commitPrepared(clientId);

        assertNotEquals(OperationStatus.OK, response.getStatus());
        assertEquals(OperationStatus.NOT_FOUND, findStatus("kept"));
    }

    @Test
    public void test_commit_applies_every_buffered_op_when_the_slice_is_intact() {
        final var clientId = clientTracker.registerForwardedClient("op-count-ok");
        TransactionOperationHelper.start(clientId);
        bufferSave(clientId, "first");
        bufferSave(clientId, "second");

        assertEquals(OperationStatus.OK, TwoPhaseParticipant.commitPrepared(clientId).getStatus());

        assertEquals(OperationStatus.OK, findStatus("first"));
        assertEquals(OperationStatus.OK, findStatus("second"));
    }
}
