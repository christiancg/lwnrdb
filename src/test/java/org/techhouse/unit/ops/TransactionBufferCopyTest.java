package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
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
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionBufferCopyTest {
    private static final Configuration configuration = Configuration.getInstance();
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private UUID clientId;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        clientId = clientTracker.registerForwardedClient("copies");
        TestUtils.ensureAdminUser(clientTracker.getAuthenticatedUsername(clientId));
        TransactionOperationHelper.start(clientId);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TransactionOperationHelper.rollback(clientId);
        clientTracker.removeById(clientId);
        TestUtils.setPrivateField(configuration, "maxEntrySize", ConfigKey.MAX_ENTRY_SIZE.defaultValue());
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private Transaction transaction() {
        return clientTracker.getActiveTransaction(clientId);
    }

    private static JsonObject document(String id, String padding) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("pad", new JsonString(padding));
        return object;
    }

    private OperationResponse bufferSave(JsonObject object) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(object.get("_id").asJsonString().getValue());
        return TransactionOperationHelper.bufferSave(request, transaction());
    }

    private JsonObject buffered(String id) {
        return transaction().overlayFor(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)).get(id);
    }

    @Test
    public void test_the_overlay_holds_the_buffered_payload_itself() {
        assertEquals(OperationStatus.OK, bufferSave(document("shared", "p")).getStatus());
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document("bulk-a", "a"), document("bulk-b", "b")));
        assertEquals(OperationStatus.OK, TransactionOperationHelper.bufferBulkSave(bulk, transaction()).getStatus());

        final var ops = transaction().getPendingOps();
        assertSame(ops.getFirst().getPayload(), buffered("shared"),
                "the overlay entry and the buffered op share one document");
        final var bulkObjects = ops.get(1).getPayload().get(AdminTransactionEntry.OBJECTS_FIELD).asJsonArray();
        assertSame(bulkObjects.get(0), buffered("bulk-a"));
        assertSame(bulkObjects.get(1), buffered("bulk-b"));
    }

    @Test
    public void test_a_delete_hook_leaves_the_buffered_document_unchanged() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        BeforeHookTestSupport.installHook("scribbler", "scribble",
                "export default (d) => { d.pad = 'scribbled'; d.extra = 1; };", EventType.DELETED);
        assertEquals(OperationStatus.OK, bufferSave(document("victim", "original")).getStatus());
        final var payload = transaction().getPendingOps().getFirst().getPayload();

        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("victim");
        assertEquals(OperationStatus.OK, TransactionOperationHelper.bufferDelete(delete, transaction()).getStatus());

        assertEquals("original", payload.get("pad").asJsonString().getValue(),
                "the hook works on its own copy of the buffered document");
        assertFalse(payload.has("extra"));
        assertTrue(Transaction.isTombstone(buffered("victim")));
    }

    @Test
    public void test_an_oversized_buffered_save_is_refused_with_entry_too_large() throws Exception {
        TestUtils.setPrivateField(configuration, "maxEntrySize", "1Kb");

        final var single = bufferSave(document("too-big", "x".repeat(2048)));
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document("fits", "y"), document("bulk-too-big", "z".repeat(2048))));
        final var bulkResponse = TransactionOperationHelper.bufferBulkSave(bulk, transaction());

        assertEquals(ErrorCode.ENTRY_TOO_LARGE.getCode(), single.getErrorCode());
        assertEquals(ErrorCode.ENTRY_TOO_LARGE.getCode(), bulkResponse.getErrorCode());
        assertTrue(transaction().getPendingOps().isEmpty(), "a refused write buffers nothing");
        assertEquals(OperationStatus.OK, bufferSave(document("small", "s")).getStatus());
        assertEquals(1, transaction().getPendingOps().size());
    }
}
