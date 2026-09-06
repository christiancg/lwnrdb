package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.config.Configuration;
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
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorBeforeHookTest {
    private static final String ACTOR = "alice";
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
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

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("qty", new JsonNumber(2));
        object.add("price", new JsonNumber(10));
        return object;
    }

    private org.techhouse.ops.resp.OperationResponse save(String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id));
        request.set_id(id);
        return processor.processMessage(request);
    }

    private JsonObject find(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        final var response = processor.processMessage(request);
        return response instanceof FindByIdResponse found ? found.getObject() : null;
    }

    @Test
    public void test_save_writes_the_replacement_document() throws Exception {
        installHook("calc", "calc", "export default (d) => ({ ...d, total: d.qty * d.price });", EventType.CREATED,
                EventType.UPDATED);
        assertEquals(OperationStatus.OK, save("s1").getStatus());
        final var stored = find("s1");
        assertNotNull(stored);
        assertEquals(20.0, stored.get("total").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_save_rejected_by_the_hook_writes_nothing() throws Exception {
        installHook("veto", "veto", "export default (d) => { throw new Error('customerId is required'); };",
                EventType.CREATED, EventType.UPDATED);
        final var response = save("s2");
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("customerId is required"));
        assertNull(find("s2"));
    }

    @Test
    public void test_a_compliant_write_still_succeeds_after_a_rejection() throws Exception {
        installHook("veto", "veto", "export default (d) => { if (d.qty > 1) { throw new Error('too many'); } };",
                EventType.CREATED, EventType.UPDATED);
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), save("s3").getErrorCode());
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = document("s4");
        object.add("qty", new JsonNumber(1));
        request.setObject(object);
        request.set_id("s4");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        assertNotNull(find("s4"));
    }

    @Test
    public void test_a_timeout_rejects_the_write() throws Exception {
        TestUtils.setPrivateField(configuration, "beforeHookInstructionBudget", 5_000_000_000L);
        TestUtils.setPrivateField(configuration, "beforeHookTimeoutMs", 50L);
        installHook("spin", "spin", "export default (d) => { while (true) { } };", EventType.CREATED,
                EventType.UPDATED);
        assertEquals(ErrorCode.SCRIPT_TIMEOUT.getCode(), save("s5").getErrorCode());
        assertNull(find("s5"));
    }

    @Test
    public void test_bulk_save_applies_the_replacement_to_every_document() throws Exception {
        installHook("calc", "calc", "export default (d) => ({ ...d, total: d.qty * d.price });", EventType.CREATED,
                EventType.UPDATED);
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(document("b1"), document("b2")));
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        assertEquals(20.0, Objects.requireNonNull(find("b1")).get("total").asJsonNumber().getValue().doubleValue());
        assertEquals(20.0, Objects.requireNonNull(find("b2")).get("total").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_bulk_save_rejects_the_whole_request_on_the_first_veto() throws Exception {
        installHook("veto", "veto", "export default (d) => { if (d._id === 'b4') { throw new Error('no'); } };",
                EventType.CREATED, EventType.UPDATED);
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(document("b3"), document("b4"), document("b5")));
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), processor.processMessage(request).getErrorCode());
        assertNull(find("b3"));
        assertNull(find("b4"));
        assertNull(find("b5"));
    }

    // One interpreter per request: the module body must evaluate once for the whole batch.
    @Test
    public void test_bulk_save_evaluates_the_module_body_once() throws Exception {
        installHook("counter", "counter", "let opened = 0; opened++; export default (d) => ({ ...d, opened: opened });",
                EventType.CREATED, EventType.UPDATED);
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(document("c1"), document("c2"), document("c3")));
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        assertEquals(1.0, Objects.requireNonNull(find("c1")).get("opened").asJsonNumber().getValue().doubleValue());
        assertEquals(1.0, Objects.requireNonNull(find("c3")).get("opened").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_delete_vetoed_by_the_hook_leaves_the_document() throws Exception {
        assertEquals(OperationStatus.OK, save("d1").getStatus());
        installHook("lock", "lock", "export default (d) => { if (d.price === 10) { throw new Error('locked'); } };",
                EventType.DELETED);
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("d1");
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), processor.processMessage(request).getErrorCode());
        assertNotNull(find("d1"));
    }

    // The hook is handed the stored document, not the request, which carries only the _id.
    @Test
    public void test_delete_hook_receives_the_stored_document() throws Exception {
        assertEquals(OperationStatus.OK, save("d2").getStatus());
        installHook("lock", "lock", "export default (d) => { if (d.qty !== 2) { throw new Error('unexpected'); } };",
                EventType.DELETED);
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("d2");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        assertNull(find("d2"));
    }

    @Test
    public void test_delete_of_an_absent_document_runs_no_hook() throws Exception {
        installHook("lock", "lock", "export default (d) => { throw new Error('should not run'); };", EventType.DELETED);
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("never-existed");
        assertNotEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), processor.processMessage(request).getErrorCode());
    }

    // A replica applies the document the owner already transformed, so the hook must not run again.
    @Test
    public void test_before_hook_does_not_fire_on_a_replicated_apply() throws Exception {
        installHook("calc", "calc", "export default (d) => ({ ...d, total: 999 });", EventType.CREATED,
                EventType.UPDATED);
        final var payload = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(document("r1")), List.of(), List.of(1L));
        ReplicatedApplyHelper.apply(payload);
        final var stored = find("r1");
        assertNotNull(stored);
        assertFalse(stored.has("total"), "a replicated apply must store the owner's document verbatim");
    }

    @Test
    public void test_triggers_disabled_runs_no_hook() throws Exception {
        installHook("veto", "veto", "export default (d) => { throw new Error('no'); };", EventType.CREATED,
                EventType.UPDATED);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        assertEquals(OperationStatus.OK, save("s6").getStatus());
        assertNotNull(find("s6"));
    }

    // The common bulk-insert shape: no _id at all, so the hook must still see each document and its
    // replacement must survive the id being assigned by the write path.
    @Test
    public void test_bulk_save_without_ids_still_runs_the_hook() throws Exception {
        installHook("calc", "calc", "export default (d) => ({ ...d, total: d.qty * d.price });", EventType.CREATED,
                EventType.UPDATED);
        final var first = document("ignored1");
        final var second = document("ignored2");
        first.remove("_id");
        second.remove("_id");
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(first, second));
        final var response = processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        final var inserted = ((org.techhouse.ops.resp.BulkSaveResponse) response).getInserted();
        assertEquals(2, inserted.size());
        for (final var id : inserted) {
            assertEquals(20.0, Objects.requireNonNull(find(id)).get("total").asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_bulk_save_without_ids_can_be_vetoed() throws Exception {
        installHook("veto", "veto", "export default (d) => { throw new Error('no'); };", EventType.CREATED,
                EventType.UPDATED);
        final var object = document("ignored");
        object.remove("_id");
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(object));
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), processor.processMessage(request).getErrorCode());
    }
}
