package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.admin.AdminPageHelper;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionCommitMarkerTest {
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void resetClients() throws Exception {
        TestUtils.resetClients();
    }

    private UUID transactionSaving(String clientName, String id) {
        final var clientId = clientTracker.registerForwardedClient(clientName);
        TransactionOperationHelper.start(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        request.setObject(object);
        request.set_id(id);
        TransactionOperationHelper.bufferSave(request, clientTracker.getActiveTransaction(clientId));
        return clientId;
    }

    private boolean isVisible(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request) instanceof FindByIdResponse found && found.getObject() != null;
    }

    @Test
    public void test_a_commit_whose_marker_page_row_fails_still_commits() {
        final var clientId = transactionSaving("marker-page-row", "page-row-failed");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var txId = transaction.getTransactionId().toString();
        final var opIds = new ArrayList<>(transaction.getBufferedOpIds());

        final OperationStatus status;
        try (var mocked = mockStatic(AdminPageHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> AdminPageHelper.baseUpdateEntryCount(eq(Globals.ADMIN_DB_NAME),
                    eq(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME), any(), anyList(), anyBoolean()))
                    .thenThrow(new IOException("disk full"));
            status = TransactionOperationHelper.commit(clientId).getStatus();
        }

        assertEquals(OperationStatus.OK, status, "a marker that landed must not be reported as a failed commit");
        assertFalse(TxCommitLog.localCommitTxIds().contains(txId), "a finished commit leaves nothing to replay");
        opIds.forEach(opId -> assertNull(cache.getPkIndexTransaction(opId)));
        assertTrue(isVisible("page-row-failed"));
    }

    @Test
    public void test_a_commit_whose_marker_never_landed_leaves_nothing_to_replay() {
        final var clientId = transactionSaving("marker-not-landed", "marker-not-landed");
        final var txId = clientTracker.getActiveTransaction(clientId).getTransactionId().toString();

        final String errorCode;
        try (var mocked = mockStatic(TxCommitLog.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> TxCommitLog.recordLocalCommit(any(), any(), any()))
                    .thenThrow(new IOException("disk full"));
            errorCode = TransactionOperationHelper.commit(clientId).getErrorCode();
        }

        assertEquals(ErrorCode.ERROR_TRANSACTION.getCode(), errorCode);
        assertFalse(TxCommitLog.localCommitTxIds().contains(txId));
        assertNull(clientTracker.getActiveTransaction(clientId), "a commit that never reached its marker deregisters");
        assertFalse(isVisible("marker-not-landed"));
    }
}
