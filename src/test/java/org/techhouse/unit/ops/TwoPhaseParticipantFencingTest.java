package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TwoPhaseParticipantFencingTest {

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void freshCollection() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("value", new JsonString("v"));
        return object;
    }

    private UUID participantWithAnUnapplicableOp() throws Exception {
        final var clientId = clientTracker.registerForwardedClient("participant");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("fenced"));
        request.set_id("fenced");
        TransactionOperationHelper.bufferSave(request, transaction);
        final var corrupt = new AdminTransactionEntry(transaction.getTransactionId().toString(), "client", 99,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, TestGlobals.COLL, new JsonObject());
        AdminOperationHelper.saveTransactionOp(corrupt);
        transaction.getBufferedOpIds().add(corrupt.get_id());
        return clientId;
    }

    @Test
    public void test_a_commit_that_cannot_finish_applying_keeps_its_locks() throws Exception {
        final var clientId = participantWithAnUnapplicableOp();
        final var transaction = clientTracker.getActiveTransaction(clientId);
        assertFalse(transaction.getHeldLocks().isEmpty(), "the buffered write should hold a lock");

        final var response = TwoPhaseParticipant.commitPrepared(clientId);

        assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(), response.getErrorCode(),
                "releasing the locks over a half-applied slice lets a foreign write land above preparedVersion,"
                        + " and the replay then fences that id out permanently");
        assertNotNull(clientTracker.getActiveTransaction(clientId),
                "the transaction stays registered so recovery can still name its locks");
        assertFalse(transaction.getHeldLocks().isEmpty(), "the collections stay fenced until recovery finishes");

        TestUtils.releaseAllLocks();
        clientTracker.clearActiveTransaction(clientId);
        clientTracker.clearTransactionState(clientId);
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_a_commit_that_applies_cleanly_still_releases_its_locks() {
        final var clientId = clientTracker.registerForwardedClient("participant");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("clean"));
        request.set_id("clean");
        TransactionOperationHelper.bufferSave(request, transaction);

        final var response = TwoPhaseParticipant.commitPrepared(clientId);

        assertEquals(org.techhouse.ops.OperationStatus.OK, response.getStatus(),
                "the fencing path must not disturb an ordinary prepared commit");
        assertTrue(transaction.getHeldLocks().isEmpty(), "a finished commit releases everything it took");
        clientTracker.removeById(clientId);
    }
}
