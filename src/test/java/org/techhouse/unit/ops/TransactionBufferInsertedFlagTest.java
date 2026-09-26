package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.Socket;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionBufferInsertedFlagTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

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
    void reset() throws Exception {
        TestUtils.resetClients();
    }

    private UUID newClient() {
        final var socket = mock(Socket.class);
        final var address = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(address);
        when(address.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    private static JsonObject document(String id, int value) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("value", new JsonNumber(value));
        return object;
    }

    private SaveResponse save(String id, int value, UUID clientId) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id, value));
        request.set_id(id);
        final var response = processor.processMessage(request, clientId);
        assertInstanceOf(SaveResponse.class, response, String.valueOf(response.getMessage()));
        return (SaveResponse) response;
    }

    private void commit(UUID clientId) {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CommitTransactionRequest(), clientId).getStatus());
    }

    @Test
    public void test_a_buffered_insert_reports_inserted() {
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        assertTrue(save("tx-new", 1, client).isInserted());
        commit(client);
    }

    @Test
    public void test_a_buffered_update_reports_not_inserted() {
        final var committed = newClient();
        assertTrue(save("tx-existing", 1, committed).isInserted());

        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        assertFalse(save("tx-existing", 2, client).isInserted());
        commit(client);
    }

    @Test
    public void test_an_id_inserted_earlier_in_the_same_transaction_reports_not_inserted() {
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        assertTrue(save("tx-twice", 1, client).isInserted());
        assertFalse(save("tx-twice", 2, client).isInserted());
        commit(client);
    }

    @Test
    public void test_an_id_deleted_earlier_in_the_same_transaction_reports_inserted() {
        final var committed = newClient();
        assertTrue(save("tx-revived", 1, committed).isInserted());

        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        final var deletion = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        deletion.set_id("tx-revived");
        assertEquals(OperationStatus.OK, processor.processMessage(deletion, client).getStatus());
        assertTrue(save("tx-revived", 2, client).isInserted());
        commit(client);
    }

    @Test
    public void test_a_non_transactional_save_still_reports_the_same_way() {
        final var client = newClient();
        assertTrue(save("plain", 1, client).isInserted());
        assertFalse(save("plain", 2, client).isInserted());
    }
}
