package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionBeforeHookSizeTest {
    private static final long SMALL_ENTRY_CAP = 1_000L;
    private static final long DEFAULT_ENTRY_CAP = 1_048_576L;
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUp() throws Exception {
        BeforeHookTestSupport.setUpAll();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxEntrySize", DEFAULT_ENTRY_CAP);
        BeforeHookTestSupport.tearDownAll();
    }

    @BeforeEach
    void reset() throws Exception {
        BeforeHookTestSupport.reset();
        TestUtils.setPrivateField(configuration, "maxEntrySize", SMALL_ENTRY_CAP);
    }

    private static void installBlobStrippingHook() throws Exception {
        BeforeHookTestSupport.installHook("strip", "strip",
                "export default (d) => { const { blob, ...rest } = d; return rest; };", EventType.CREATED,
                EventType.UPDATED);
    }

    private UUID transactionalClient() {
        final var client = BeforeHookTestSupport.newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        return client;
    }

    private static JsonObject oversized(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("blob", new JsonString("x".repeat(3_000)));
        return object;
    }

    private OperationResponse save(String id, UUID clientId) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(oversized(id));
        request.set_id(id);
        return clientId == null ? processor.processMessage(request) : processor.processMessage(request, clientId);
    }

    private OperationResponse bulkSave(UUID clientId, String... ids) {
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(java.util.Arrays.stream(ids).map(TransactionBeforeHookSizeTest::oversized).toList());
        return processor.processMessage(request, clientId);
    }

    private void rollback(UUID clientId) {
        processor.processMessage(new RollbackTransactionRequest(), clientId);
    }

    @Test
    public void test_a_hook_that_shrinks_an_oversized_document_is_accepted_in_and_out_of_a_transaction()
            throws Exception {
        installBlobStrippingHook();

        assertEquals(OperationStatus.OK, save("standalone", null).getStatus());
        final var client = transactionalClient();
        try {
            assertEquals(OperationStatus.OK, save("buffered", client).getStatus(),
                    "the transaction must size-check the document the hook produced, as the standalone path does");
        } finally {
            rollback(client);
        }
    }

    @Test
    public void test_a_hook_that_shrinks_an_oversized_bulk_is_accepted_in_a_transaction() throws Exception {
        installBlobStrippingHook();
        final var client = transactionalClient();
        try {
            assertEquals(OperationStatus.OK, bulkSave(client, "bulk-a", "bulk-b").getStatus());
        } finally {
            rollback(client);
        }
    }

    @Test
    public void test_an_oversized_document_without_a_hook_is_refused_in_a_transaction() {
        final var client = transactionalClient();
        try {
            assertEquals(ErrorCode.ENTRY_TOO_LARGE.getCode(), save("refused", client).getErrorCode());
            assertEquals(ErrorCode.ENTRY_TOO_LARGE.getCode(), bulkSave(client, "refused-bulk").getErrorCode());
        } finally {
            rollback(client);
        }
    }

    @Test
    public void test_a_duplicate_bulk_id_is_still_refused_before_any_hook() {
        final var client = transactionalClient();
        try {
            assertEquals(ErrorCode.DUPLICATE_ID.getCode(), bulkSave(client, "twice", "twice").getErrorCode());
        } finally {
            rollback(client);
        }
    }
}
