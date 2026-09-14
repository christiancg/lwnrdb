package org.techhouse.unit.ops;

import java.net.InetAddress;
import java.net.Socket;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import static org.junit.jupiter.api.Assertions.*;

public class TransactionEntrySizeTest {
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

    private UUID newClient() {
        final var socket = Mockito.mock(Socket.class);
        final var addr = Mockito.mock(InetAddress.class);
        Mockito.when(socket.getInetAddress()).thenReturn(addr);
        Mockito.when(addr.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    private static final int PAD_OVERHEAD = 11;

    private static JsonObject documentJustUnderTheCap(int headroom) {
        final var max = (int) Configuration.getInstance().getMaxEntrySize();
        final var object = new JsonObject();
        object.addProperty("pad", "x".repeat(max - PAD_OVERHEAD - headroom));
        return object;
    }

    @Test
    public void test_document_just_under_the_cap_without_an_id_is_refused_at_buffer_time() {
        final var measured = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, documentJustUnderTheCap(10))
                .byteSize();
        assertTrue(measured > Configuration.getInstance().getMaxEntrySize(),
                "precondition: with a generated _id the document must exceed the cap");

        final var object = documentJustUnderTheCap(10);
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);

        final var response = processor.processMessage(request, clientId);
        assertEquals("400-2", response.getErrorCode(),
                "the buffer must size the entry after the generated _id is injected");
    }

    @Test
    public void test_document_with_room_for_the_generated_id_is_accepted() {
        final var object = documentJustUnderTheCap(200);
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);

        final var response = processor.processMessage(request, clientId);
        assertNull(response.getErrorCode());
    }
}
