package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionMissingCollectionTest {
    private static final String MISSING = "missing";

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private UUID startedTransaction() {
        final var socket = Mockito.mock(Socket.class);
        final var addr = Mockito.mock(InetAddress.class);
        Mockito.when(socket.getInetAddress()).thenReturn(addr);
        Mockito.when(addr.getHostAddress()).thenReturn("127.0.0.1");
        final var clientId = clientTracker.addClient(socket);
        processor.processMessage(new StartTransactionRequest(), clientId);
        return clientId;
    }

    private static JsonObject doc() {
        final var object = new JsonObject();
        object.addProperty("_id", "a");
        object.addProperty("v", 1);
        return object;
    }

    @Test
    public void test_a_buffered_save_naming_an_unknown_collection_is_refused_at_buffer_time() {
        final var request = new SaveRequest(TestGlobals.DB, MISSING);
        request.setObject(doc());
        assertEquals("404-11", processor.processMessage(request, startedTransaction()).getErrorCode(),
                "acknowledging the buffer and failing the whole commit hides the cause from the client");
    }

    @Test
    public void test_a_buffered_bulk_save_naming_an_unknown_collection_is_refused_at_buffer_time() {
        final var request = new BulkSaveRequest(TestGlobals.DB, MISSING);
        request.setObjects(List.of(doc()));
        assertEquals("404-11", processor.processMessage(request, startedTransaction()).getErrorCode());
    }

    @Test
    public void test_a_buffered_delete_naming_an_unknown_collection_is_refused_at_buffer_time() {
        final var request = new DeleteRequest(TestGlobals.DB, MISSING);
        request.set_id("a");
        assertEquals("404-11", processor.processMessage(request, startedTransaction()).getErrorCode());
    }

    @Test
    public void test_a_buffered_write_to_a_known_collection_still_succeeds() {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(doc());
        final var response = processor.processMessage(request, startedTransaction());
        assertNull(response.getErrorCode());
    }
}
