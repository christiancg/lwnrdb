package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionBeforeHookSizeTest {
    private static final String ACTOR = "alice";
    private static final long SMALL_ENTRY_CAP = 1_000L;
    private static final long DEFAULT_ENTRY_CAP = 1_048_576L;
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxEntrySize", DEFAULT_ENTRY_CAP);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "beforeHookInstructionBudget", 200_000L);
        TestUtils.setPrivateField(configuration, "beforeHookTimeoutMs", 2_000L);
        TestUtils.setPrivateField(configuration, "maxEntrySize", SMALL_ENTRY_CAP);
        TestUtils.resetClients();
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
    }

    private void installBlobStrippingHook() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "strip",
                "export default (d) => { const { blob, ...rest } = d; return rest; };"), ACTOR);
        final var existing = new ArrayList<>(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL));
        existing.add(new TriggerDefinition("strip", new LinkedHashSet<>(Set.of(EventType.CREATED, EventType.UPDATED)),
                "strip", TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_BEFORE, false, true, ACTOR, 1L, 1L,
                1L, ACTOR));
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, existing);
    }

    private UUID transactionalClient() {
        final var socket = mock(Socket.class);
        final var address = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(address);
        when(address.getHostAddress()).thenReturn("127.0.0.1");
        final var client = clientTracker.addClient(socket);
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
