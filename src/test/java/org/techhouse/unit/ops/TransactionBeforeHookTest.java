package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
import org.techhouse.ejson.elements.JsonNumber;
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
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionBeforeHookTest {
    private static final String ACTOR = "alice";
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
        TestUtils.resetClients();
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
    }

    private void installHook(String name, String procedure, String source, EventType... events) throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, procedure, source), ACTOR);
        final var existing = new ArrayList<>(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL));
        existing.add(new TriggerDefinition(name, new LinkedHashSet<>(Set.of(events)), procedure,
                TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_BEFORE, false, true, ACTOR, 1L, 1L, 1L,
                ACTOR));
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, existing);
    }

    private UUID newClient() {
        final var socket = mock(Socket.class);
        final var address = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(address);
        when(address.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("qty", new JsonNumber(2));
        object.add("price", new JsonNumber(10));
        return object;
    }

    private org.techhouse.ops.resp.OperationResponse save(String id, UUID clientId) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id));
        request.set_id(id);
        return processor.processMessage(request, clientId);
    }

    private JsonObject find(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        final var response = processor.processMessage(request);
        return response instanceof FindByIdResponse found ? found.getObject() : null;
    }

    @Test
    public void test_buffer_save_applies_the_replacement() throws Exception {
        installHook("calc", "calc", "export default (d) => ({ ...d, total: d.qty * d.price });", EventType.CREATED,
                EventType.UPDATED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        assertEquals(OperationStatus.OK, save("t1", client).getStatus());
        assertEquals(OperationStatus.OK, processor.processMessage(new CommitTransactionRequest(), client).getStatus());
        assertEquals(20.0, Objects.requireNonNull(find("t1")).get("total").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_buffer_save_rejection_buffers_nothing() throws Exception {
        installHook("veto", "veto", "export default (d) => { throw new Error('nope'); };", EventType.CREATED,
                EventType.UPDATED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), save("t2", client).getErrorCode());
        processor.processMessage(new CommitTransactionRequest(), client);
        assertNull(find("t2"));
    }

    // The ordering that matters: a hook can inflate a document past maxEntrySize, so the replacement has
    // to be size-checked rather than only the caller's own document.
    @Test
    public void test_a_replacement_is_size_checked() throws Exception {
        installHook("fat", "fat", "export default (d) => ({ ...d, blob: 'x'.repeat(3000) });", EventType.CREATED,
                EventType.UPDATED);
        TestUtils.setPrivateField(configuration, "maxEntrySize", 1_000L);
        try {
            final var client = newClient();
            processor.processMessage(new StartTransactionRequest(), client);
            assertEquals(ErrorCode.ENTRY_TOO_LARGE.getCode(), save("t3", client).getErrorCode());
        } finally {
            TestUtils.setPrivateField(configuration, "maxEntrySize", 1_048_576L);
        }
    }

    @Test
    public void test_buffer_bulk_save_applies_the_replacement() throws Exception {
        installHook("calc", "calc", "export default (d) => ({ ...d, total: d.qty * d.price });", EventType.CREATED,
                EventType.UPDATED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(document("t4"), document("t5")));
        assertEquals(OperationStatus.OK, processor.processMessage(request, client).getStatus());
        assertEquals(OperationStatus.OK, processor.processMessage(new CommitTransactionRequest(), client).getStatus());
        assertEquals(20.0, Objects.requireNonNull(find("t4")).get("total").asJsonNumber().getValue().doubleValue());
        assertEquals(20.0, Objects.requireNonNull(find("t5")).get("total").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_buffer_bulk_save_rejection_buffers_nothing() throws Exception {
        installHook("veto", "veto", "export default (d) => { if (d._id === 't7') { throw new Error('no'); } };",
                EventType.CREATED, EventType.UPDATED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(document("t6"), document("t7")));
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(),
                processor.processMessage(request, client).getErrorCode());
        processor.processMessage(new CommitTransactionRequest(), client);
        assertNull(find("t6"));
        assertNull(find("t7"));
    }

    @Test
    public void test_buffer_delete_veto_leaves_the_document() throws Exception {
        assertEquals(OperationStatus.OK, save("t8", newClient()).getStatus());
        installHook("lock", "lock", "export default (d) => { throw new Error('locked'); };", EventType.DELETED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("t8");
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(),
                processor.processMessage(request, client).getErrorCode());
        processor.processMessage(new CommitTransactionRequest(), client);
        assertNotNull(find("t8"));
    }

    @Test
    public void test_buffer_delete_proceeds_when_the_hook_accepts() throws Exception {
        assertEquals(OperationStatus.OK, save("t9", newClient()).getStatus());
        installHook("ok", "okhook", "export default (d) => { };", EventType.DELETED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("t9");
        assertEquals(OperationStatus.OK, processor.processMessage(request, client).getStatus());
        assertEquals(OperationStatus.OK, processor.processMessage(new CommitTransactionRequest(), client).getStatus());
        assertNull(find("t9"));
    }
}
