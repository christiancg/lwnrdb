package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.InetAddress;
import java.net.Socket;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionWritesDeleteHookTest {
    private static final String ACTOR = "alice";
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private UUID client;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "allow", "export default (d) => {};"), ACTOR);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("guard", new LinkedHashSet<>(Set.of(EventType.DELETED)), "allow",
                        TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_BEFORE, false, true, ACTOR, 1L, 1L,
                        1L, ACTOR)));
        final var socket = mock(Socket.class);
        final var address = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(address);
        when(address.getHostAddress()).thenReturn("127.0.0.1");
        client = clientTracker.addClient(socket);
    }

    @AfterEach
    void tearDown() throws Exception {
        processor.processMessage(new RollbackTransactionRequest(), client);
        clientTracker.removeById(client);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void saveCommitted(String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        request.setObject(object);
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private OperationResponse bufferDelete(String id) {
        processor.processMessage(new StartTransactionRequest(), client);
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request, client);
    }

    private void makeTheVictimUnreadable() {
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "victim");
        final var folder = new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);
        for (final var page : Objects.requireNonNull(folder.listFiles((_, name) -> name.endsWith(".dat")))) {
            assertTrue(page.delete());
        }
    }

    @Test
    public void test_an_unreadable_document_refuses_the_delete() {
        saveCommitted("victim");
        makeTheVictimUnreadable();

        final var response = bufferDelete("victim");

        assertEquals(ErrorCode.ERROR_DELETING.getCode(), response.getErrorCode(),
                "a veto hook that never ran must refuse the delete, as the standalone path does");
    }

    @Test
    public void test_a_missing_document_runs_no_hook() {
        assertNotEquals(ErrorCode.ERROR_DELETING.getCode(), bufferDelete("never-saved").getErrorCode());
    }

    @Test
    public void test_a_readable_document_still_runs_the_hook_and_buffers() {
        saveCommitted("kept");

        final var response = bufferDelete("kept");
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
    }
}
