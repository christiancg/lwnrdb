package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerFencedCommitTest {
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document() {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString("keep"));
        object.add("value", new JsonString("v"));
        return object;
    }

    @Test
    public void test_a_fenced_commit_keeps_the_transaction_registered() throws Exception {
        final var clientId = clientTracker.registerForwardedClient("fenced-owner");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document());
        request.set_id("keep");
        TransactionOperationHelper.bufferSave(request, transaction);
        final var corrupt = new AdminTransactionEntry(transaction.getTransactionId().toString(), "client", 99,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, TestGlobals.COLL, new JsonObject());
        AdminOperationHelper.saveTransactionOp(corrupt);
        transaction.getBufferedOpIds().add(corrupt.get_id());

        final var response = TransactionOperationHelper.commit(clientId);

        assertEquals("500-33", response.getErrorCode());
        assertNotNull(clientTracker.getActiveTransaction(clientId),
                "the fence keeps the write locks and the commit-log marker, so the registration that records"
                        + " who holds them has to survive too");
        assertFalse(transaction.getHeldLocks().isEmpty(), "the fenced collections stay locked");
        TransactionOperationHelper.abortInPlace(clientId);
    }
}
