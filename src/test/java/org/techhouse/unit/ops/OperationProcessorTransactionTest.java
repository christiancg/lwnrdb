package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.CommitTransactionResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.RollbackTransactionResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.ops.resp.StartTransactionResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorTransactionTest {
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    public static void tearDownAll() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void resetClientTracker() throws Exception {
        TestUtils.resetClients();
    }

    private UUID newClient() {
        final var socket = mock(Socket.class);
        final var addr = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(addr);
        when(addr.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    private SaveRequest saveRequest(String id, String field, String value) {
        final var req = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString(id));
        obj.add(field, new JsonString(value));
        req.setObject(obj);
        req.set_id(id);
        return req;
    }

    @Test
    public void test_start_transaction_returns_id() {
        final var clientId = newClient();
        final var response = processor.processMessage(new StartTransactionRequest(), clientId);
        assertInstanceOf(StartTransactionResponse.class, response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(((StartTransactionResponse) response).getTransactionId());
        assertNotNull(clientTracker.getActiveTransaction(clientId));
        processor.processMessage(new RollbackTransactionRequest(), clientId);
    }

    @Test
    public void test_start_when_already_active_returns_409_3() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var response = processor.processMessage(new StartTransactionRequest(), clientId);
        assertEquals("409-3", response.getErrorCode());
        processor.processMessage(new RollbackTransactionRequest(), clientId);
    }

    @Test
    public void test_commit_with_no_active_transaction_returns_409_4() {
        final var clientId = newClient();
        final var response = processor.processMessage(new CommitTransactionRequest(), clientId);
        assertEquals("409-4", response.getErrorCode());
    }

    @Test
    public void test_rollback_with_no_active_transaction_returns_409_4() {
        final var clientId = newClient();
        final var response = processor.processMessage(new RollbackTransactionRequest(), clientId);
        assertEquals("409-4", response.getErrorCode());
    }

    @Test
    public void test_buffered_save_not_visible_to_other_clients_until_commit() {
        final var txnClient = newClient();
        final var otherClient = newClient();
        processor.processMessage(new StartTransactionRequest(), txnClient);

        final var saveResponse = processor.processMessage(saveRequest("txn-buf-1", "name", "alice"), txnClient);
        assertInstanceOf(SaveResponse.class, saveResponse);

        // Another connection (no transaction) must not see the uncommitted write.
        final var findOther = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        findOther.set_id("txn-buf-1");
        assertEquals(OperationStatus.NOT_FOUND, processor.processMessage(findOther, otherClient).getStatus());

        processor.processMessage(new CommitTransactionRequest(), txnClient);

        // After commit it is visible to everyone.
        assertEquals(OperationStatus.OK, processor.processMessage(findOther, otherClient).getStatus());
    }

    @Test
    public void test_read_your_writes_find_by_id() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("txn-ryw-1", "name", "bob"), clientId);

        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("txn-ryw-1");
        final var response = processor.processMessage(find, clientId);
        assertInstanceOf(FindByIdResponse.class, response);
        assertEquals("bob", ((FindByIdResponse) response).getObject().get("name").asJsonString().getValue());

        processor.processMessage(new RollbackTransactionRequest(), clientId);
    }

    @Test
    public void test_buffered_delete_reads_as_not_found() {
        final var clientId = newClient();
        // Commit an initial document, then in a new transaction delete it and read it back.
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("txn-del-1", "name", "carol"), clientId);
        processor.processMessage(new CommitTransactionRequest(), clientId);

        processor.processMessage(new StartTransactionRequest(), clientId);
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("txn-del-1");
        assertEquals(OperationStatus.OK, processor.processMessage(delete, clientId).getStatus());

        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("txn-del-1");
        assertEquals(OperationStatus.NOT_FOUND, processor.processMessage(find, clientId).getStatus());

        processor.processMessage(new RollbackTransactionRequest(), clientId);
        // Rolled back — the document is still there for a fresh reader.
        assertEquals(OperationStatus.OK, processor.processMessage(find, newClient()).getStatus());
    }

    @Test
    public void test_buffered_delete_of_missing_id_returns_not_found() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("txn-does-not-exist");
        assertEquals(OperationStatus.NOT_FOUND, processor.processMessage(delete, clientId).getStatus());
        processor.processMessage(new RollbackTransactionRequest(), clientId);
    }

    @Test
    public void test_aggregate_reflects_buffered_insert_update_and_delete() {
        final var clientId = newClient();
        // Seed a committed document that the transaction will update.
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("txn-agg-upd", "status", "old"), clientId);
        processor.processMessage(saveRequest("txn-agg-del", "status", "keep"), clientId);
        processor.processMessage(new CommitTransactionRequest(), clientId);

        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("txn-agg-ins", "status", "new"), clientId); // insert
        processor.processMessage(saveRequest("txn-agg-upd", "status", "updated"), clientId); // update
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("txn-agg-del");
        processor.processMessage(delete, clientId); // delete

        final var aggregate = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        aggregate.setAggregationSteps(new ArrayList<>());
        final var response = processor.processMessage(aggregate, clientId);
        assertInstanceOf(AggregateResponse.class, response);
        final var results = ((AggregateResponse) response).getResults();
        final var ids = idsOf(results);
        assertTrue(ids.contains("txn-agg-ins"), "buffered insert should appear");
        assertFalse(ids.contains("txn-agg-del"), "buffered delete should be hidden");
        final var updated = results.stream().filter(o -> "txn-agg-upd".equals(o.get("_id").asJsonString().getValue()))
                .findFirst().orElseThrow();
        assertEquals("updated", updated.get("status").asJsonString().getValue());

        processor.processMessage(new RollbackTransactionRequest(), clientId);
    }

    private List<String> idsOf(List<JsonObject> results) {
        final var ids = new ArrayList<String>();
        for (final var obj : results) {
            ids.add(obj.get("_id").asJsonString().getValue());
        }
        return ids;
    }

    @Test
    public void test_buffered_bulk_save_classifies_and_commits() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var objects = new ArrayList<JsonObject>();
        final var a = new JsonObject();
        a.add("_id", new JsonString("txn-bulk-1"));
        final var b = new JsonObject();
        b.add("_id", new JsonString("txn-bulk-2"));
        objects.add(a);
        objects.add(b);
        bulk.setObjects(objects);
        final var response = processor.processMessage(bulk, clientId);
        assertInstanceOf(BulkSaveResponse.class, response);
        assertEquals(2, ((BulkSaveResponse) response).getInserted().size());
        processor.processMessage(new CommitTransactionRequest(), clientId);

        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("txn-bulk-2");
        assertEquals(OperationStatus.OK, processor.processMessage(find, newClient()).getStatus());
    }

    @Test
    public void test_rollback_discards_buffered_writes() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("txn-rb-1", "name", "dave"), clientId);
        final var response = processor.processMessage(new RollbackTransactionRequest(), clientId);
        assertInstanceOf(RollbackTransactionResponse.class, response);

        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("txn-rb-1");
        assertEquals(OperationStatus.NOT_FOUND, processor.processMessage(find, newClient()).getStatus());
    }

    @Test
    public void test_commit_releases_locks() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("txn-lock-1", "name", "erin"), clientId);
        final var response = processor.processMessage(new CommitTransactionRequest(), clientId);
        assertInstanceOf(CommitTransactionResponse.class, response);
        // The write lock must have been released: acquire+release it directly, which throws nothing.
        final var locks = IocContainer.get(ResourceLocking.class);
        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL));
        locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_ddl_operation_during_transaction_returns_409_6() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var response = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "someColl"),
                clientId);
        assertEquals("409-6", response.getErrorCode());
        assertEquals(OperationStatus.ERROR, response.getStatus());
        processor.processMessage(new RollbackTransactionRequest(), clientId);
    }

    @Test
    public void test_buffered_bulk_save_classifies_existing_id_as_updated() {
        final var clientId = newClient();
        // Commit a document, then bulk-save it again (plus a new one) inside a transaction.
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("txn-bulkupd-1", "v", "old"), clientId);
        processor.processMessage(new CommitTransactionRequest(), clientId);

        processor.processMessage(new StartTransactionRequest(), clientId);
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var objects = new ArrayList<JsonObject>();
        final var existing = new JsonObject();
        existing.add("_id", new JsonString("txn-bulkupd-1"));
        final var fresh = new JsonObject();
        fresh.add("_id", new JsonString("txn-bulkupd-2"));
        objects.add(existing);
        objects.add(fresh);
        bulk.setObjects(objects);
        final var response = (BulkSaveResponse) processor.processMessage(bulk, clientId);
        assertEquals(1, response.getUpdated().size());
        assertEquals(1, response.getInserted().size());
        processor.processMessage(new RollbackTransactionRequest(), clientId);
    }

    @Test
    public void test_delete_of_buffered_insert_in_same_transaction() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("txn-di-1", "v", "temp"), clientId);
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("txn-di-1");
        assertEquals(OperationStatus.OK, processor.processMessage(delete, clientId).getStatus());
        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("txn-di-1");
        assertEquals(OperationStatus.NOT_FOUND, processor.processMessage(find, clientId).getStatus());
        processor.processMessage(new RollbackTransactionRequest(), clientId);
    }

    @Test
    public void test_buffered_save_without_id_generates_id() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add("name", new JsonString("no-id"));
        save.setObject(obj);
        final var response = (SaveResponse) processor.processMessage(save, clientId);
        assertNotNull(response.get_id());
        // Read-your-writes returns the generated document.
        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id(response.get_id());
        assertEquals(OperationStatus.OK, processor.processMessage(find, clientId).getStatus());
        processor.processMessage(new RollbackTransactionRequest(), clientId);
    }

    @Test
    public void test_buffered_save_too_large_returns_400_2() throws Exception {
        final var config = org.techhouse.config.Configuration.getInstance();
        final var original = config.getMaxEntrySize();
        TestUtils.setPrivateField(config, "maxEntrySize", 5L);
        final var clientId = newClient();
        try {
            processor.processMessage(new StartTransactionRequest(), clientId);
            final var response = processor.processMessage(saveRequest("txn-big-1", "name", "way-too-large"), clientId);
            assertEquals("400-2", response.getErrorCode());
        } finally {
            TestUtils.setPrivateField(config, "maxEntrySize", original);
            processor.processMessage(new RollbackTransactionRequest(), clientId);
        }
    }

    @Test
    public void test_buffered_bulk_save_too_large_returns_400_2() throws Exception {
        final var config = org.techhouse.config.Configuration.getInstance();
        final var original = config.getMaxEntrySize();
        TestUtils.setPrivateField(config, "maxEntrySize", 5L);
        final var clientId = newClient();
        try {
            processor.processMessage(new StartTransactionRequest(), clientId);
            final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
            final var objects = new ArrayList<JsonObject>();
            final var obj = new JsonObject();
            obj.add("_id", new JsonString("txn-bigbulk-1"));
            obj.add("name", new JsonString("too-large"));
            objects.add(obj);
            bulk.setObjects(objects);
            final var response = processor.processMessage(bulk, clientId);
            assertEquals("400-2", response.getErrorCode());
        } finally {
            TestUtils.setPrivateField(config, "maxEntrySize", original);
            processor.processMessage(new RollbackTransactionRequest(), clientId);
        }
    }

    @Test
    public void test_buffered_bulk_save_duplicate_id_returns_400_3() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var objects = new ArrayList<JsonObject>();
        final var a = new JsonObject();
        a.add("_id", new JsonString("txn-dup-1"));
        final var b = new JsonObject();
        b.add("_id", new JsonString("txn-dup-1"));
        objects.add(a);
        objects.add(b);
        bulk.setObjects(objects);
        final var response = processor.processMessage(bulk, clientId);
        assertEquals("400-3", response.getErrorCode());
        processor.processMessage(new RollbackTransactionRequest(), clientId);
    }

    @Test
    public void test_start_transaction_response_setter() {
        final var response = new StartTransactionResponse("Transaction started", "txn-x");
        response.setTransactionId("txn-y");
        assertEquals("txn-y", response.getTransactionId());
    }

    @Test
    public void test_operations_without_transaction_behave_normally() {
        final var clientId = newClient();
        // No START_TRANSACTION: a plain SAVE writes straight through and is immediately visible.
        final var save = processor.processMessage(saveRequest("txn-none-1", "name", "frank"), clientId);
        assertEquals(OperationStatus.OK, save.getStatus());
        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("txn-none-1");
        final OperationResponse response = processor.processMessage(find, clientId);
        assertEquals(OperationStatus.OK, response.getStatus());
    }
}
