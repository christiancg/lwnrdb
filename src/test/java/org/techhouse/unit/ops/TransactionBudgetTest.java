package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.tx.TransactionBudget;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionBudgetTest {
    private static final Configuration configuration = Configuration.getInstance();
    private static final String PADDING = "p".repeat(1_000);
    private static final long POOL_BYTES = 1_500L;
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final TransactionBudget budget = IocContainer.get(TransactionBudget.class);
    private UUID clientId;
    private long baseline;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        clientId = startedClient("budget");
        baseline = budget.reservedBytes();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TransactionOperationHelper.rollback(clientId);
        clientTracker.removeById(clientId);
        TestUtils.setPrivateField(configuration, "transactionTotalMaxBytes",
                ConfigKey.TRANSACTION_TOTAL_MAX_BYTES.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private UUID startedClient(String name) {
        final var client = clientTracker.registerForwardedClient(name);
        TestUtils.ensureAdminUser(clientTracker.getAuthenticatedUsername(client));
        TransactionOperationHelper.start(client);
        return client;
    }

    private Transaction transactionOf(UUID client) {
        return clientTracker.getActiveTransaction(client);
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("pad", new JsonString(PADDING));
        return object;
    }

    private OperationResponse bufferSave(UUID client, String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id));
        request.set_id(id);
        return TransactionOperationHelper.bufferSave(request, transactionOf(client));
    }

    private void capTotalAtOneDocumentAndAHalf() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionTotalMaxBytes", String.valueOf(baseline + POOL_BYTES));
    }

    @Test
    public void aBufferedSavePastThePoolAnswers503_21AndBuffersNothing() throws Exception {
        capTotalAtOneDocumentAndAHalf();
        assertEquals(OperationStatus.OK, bufferSave(clientId, "first").getStatus());

        final var refused = bufferSave(clientId, "second");

        assertEquals(ErrorCode.TRANSACTION_BUDGET_EXHAUSTED.getCode(), refused.getErrorCode());
        assertEquals(1, transactionOf(clientId).getPendingOps().size());
    }

    @Test
    public void theTransactionStaysUsableAfterARefusal() throws Exception {
        capTotalAtOneDocumentAndAHalf();
        bufferSave(clientId, "kept");
        bufferSave(clientId, "refused");

        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());
        assertEquals(baseline, budget.reservedBytes());
    }

    @Test
    public void aBulkSaveReservesItsWholeBatchOrNothing() throws Exception {
        capTotalAtOneDocumentAndAHalf();
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document("bulk-a"), document("bulk-b")));

        final var refused = TransactionOperationHelper.bufferBulkSave(bulk, transactionOf(clientId));

        assertEquals(ErrorCode.TRANSACTION_BUDGET_EXHAUSTED.getCode(), refused.getErrorCode());
        assertTrue(transactionOf(clientId).getPendingOps().isEmpty());
        assertEquals(baseline, budget.reservedBytes());
    }

    @Test
    public void aBufferedDeleteReservesItsBytes() {
        bufferSave(clientId, "doomed");
        final var afterSave = budget.reservedBytes();
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("doomed");

        assertEquals(OperationStatus.OK,
                TransactionOperationHelper.bufferDelete(delete, transactionOf(clientId)).getStatus());
        assertTrue(budget.reservedBytes() > afterSave);
    }

    @Test
    public void commitReleasesTheBytes() {
        bufferSave(clientId, "committed");
        assertTrue(budget.reservedBytes() > baseline);

        TransactionOperationHelper.commit(clientId);

        assertEquals(baseline, budget.reservedBytes());
    }

    @Test
    public void rollbackReleasesTheBytes() {
        bufferSave(clientId, "rolled-back");

        TransactionOperationHelper.rollback(clientId);

        assertEquals(baseline, budget.reservedBytes());
    }

    @Test
    public void aDisconnectReleasesTheBytes() {
        final var departing = startedClient("departing");
        bufferSave(departing, "departed");
        assertTrue(budget.reservedBytes() > baseline);

        clientTracker.removeById(departing);

        assertEquals(baseline, budget.reservedBytes());
    }

    @Test
    public void reRegisteringTheSameTransactionKeepsItsBytes() {
        bufferSave(clientId, "fenced");
        final var held = budget.reservedBytes();

        clientTracker.setActiveTransaction(clientId, transactionOf(clientId));

        assertEquals(held, budget.reservedBytes(), "a fenced transaction stays registered and keeps its buffer");
    }

    @Test
    public void aSecondTransactionIsAdmittedOnceTheFirstEnds() throws Exception {
        capTotalAtOneDocumentAndAHalf();
        final var other = startedClient("other");
        try {
            bufferSave(clientId, "first-holder");
            assertEquals(ErrorCode.TRANSACTION_BUDGET_EXHAUSTED.getCode(), bufferSave(other, "waiting").getErrorCode());

            TransactionOperationHelper.commit(clientId);

            assertEquals(OperationStatus.OK, bufferSave(other, "admitted").getStatus());
        } finally {
            TransactionOperationHelper.rollback(other);
            clientTracker.removeById(other);
        }
    }
}
