package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
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
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.TestTriggerRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.TestTriggerResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TestTriggerOperationTest {
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

    private void install(String procedure, String source, String timing)
            throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, procedure, source), ACTOR);
        final var existing = new ArrayList<>(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL));
        existing.add(new TriggerDefinition("v", new LinkedHashSet<>(Set.of(new EventType[]{EventType.CREATED})), procedure,
                TriggerDefinition.MODE_DOCUMENT, timing, false, true, ACTOR, 1L, 1L, 1L, ACTOR));
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, existing);
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("qty", new JsonNumber(2));
        object.add("price", new JsonNumber(10));
        return object;
    }

    private org.techhouse.ops.resp.OperationResponse test(String name, String event, JsonObject document) {
        return processor
                .processMessage(new TestTriggerRequest(TestGlobals.DB, TestGlobals.COLL, name, event, document));
    }

    private JsonObject find() {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("x6");
        final var response = processor.processMessage(request);
        return response instanceof FindByIdResponse found ? found.getObject() : null;
    }

    @Test
    public void test_reports_the_accept_decision() throws Exception {
        install("noop", "export default (d) => { };", TriggerDefinition.TIMING_BEFORE);
        final var response = test("v", "CREATED", document("x1"));
        assertInstanceOf(TestTriggerResponse.class, response, response.getMessage());
        final var tested = (TestTriggerResponse) response;
        assertEquals(TestTriggerResponse.DECISION_ACCEPT, tested.getDecision());
        assertEquals("x1", tested.getDocument().get("_id").asJsonString().getValue());
    }

    @Test
    public void test_reports_the_replace_decision_with_the_document() throws Exception {
        install("calc", "export default (d) => ({ ...d, total: d.qty * d.price });",
                TriggerDefinition.TIMING_BEFORE);
        final var tested = (TestTriggerResponse) test("v", "CREATED", document("x2"));
        assertEquals(TestTriggerResponse.DECISION_REPLACE, tested.getDecision());
        assertEquals(20.0, tested.getDocument().get("total").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_reports_the_reject_decision_with_a_reason() throws Exception {
        install("veto", "export default (d) => { throw new Error('customerId is required'); };",
                TriggerDefinition.TIMING_BEFORE);
        final var response = test("v", "CREATED", document("x3"));
        assertInstanceOf(TestTriggerResponse.class, response, response.getMessage());
        final var tested = (TestTriggerResponse) response;
        assertEquals(TestTriggerResponse.DECISION_REJECT, tested.getDecision());
        assertTrue(tested.getReason().contains("customerId is required"));
        assertNull(tested.getDocument());
    }

    // A rejecting hook names where it broke, which is the point of a dry run.
    @Test
    public void test_a_rejection_carries_a_stack() throws Exception {
        install("veto", "export default (d) => { throw new Error('boom'); };", TriggerDefinition.TIMING_BEFORE
        );
        final var tested = (TestTriggerResponse) test("v", "CREATED", document("x4"));
        assertNotNull(tested.getStack());
        assertFalse(tested.getStack().isEmpty());
    }

    @Test
    public void test_captures_console_output() throws Exception {
        install("chatty", "export default (d) => { console.log('checking ' + d._id); return d; };",
                TriggerDefinition.TIMING_BEFORE);
        final var tested = (TestTriggerResponse) test("v", "CREATED", document("x5"));
        assertEquals(List.of("checking x5"), tested.getLogs());
    }

    @Test
    public void test_writes_nothing() throws Exception {
        install("calc", "export default (d) => ({ ...d, total: 1 });", TriggerDefinition.TIMING_BEFORE
        );
        assertInstanceOf(TestTriggerResponse.class, test("v", "CREATED", document("x6")));
        assertNull(find());
    }

    @Test
    public void test_a_sandbox_abort_is_an_error_not_a_decision() throws Exception {
        TestUtils.setPrivateField(configuration, "beforeHookInstructionBudget", 5_000_000_000L);
        TestUtils.setPrivateField(configuration, "beforeHookTimeoutMs", 50L);
        install("spin", "export default (d) => { while (true) { } };", TriggerDefinition.TIMING_BEFORE
        );
        final var response = test("v", "CREATED", document("x7"));
        assertEquals(ErrorCode.SCRIPT_TIMEOUT.getCode(), response.getErrorCode());
    }

    @Test
    public void test_unknown_trigger_reports_trigger_not_found() throws Exception {
        install("noop", "export default (d) => { };", TriggerDefinition.TIMING_BEFORE);
        assertEquals(ErrorCode.TRIGGER_NOT_FOUND.getCode(),
                test("no_such_trigger", "CREATED", document("x8")).getErrorCode());
    }

    @Test
    public void test_an_after_trigger_cannot_be_tested() throws Exception {
        install("noop", "export default (d) => { };", TriggerDefinition.TIMING_AFTER);
        final var response = test("v", "CREATED", document("x9"));
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("before"));
    }

    @Test
    public void test_a_missing_procedure_reports_procedure_not_found() throws Exception {
        install("gone", "export default (d) => { };", TriggerDefinition.TIMING_BEFORE);
        fs.deleteProcedure(TestGlobals.DB, "gone");
        cache.removeProceduresForDatabase(TestGlobals.DB);
        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), test("v", "CREATED", document("x10")).getErrorCode());
    }

    @Test
    public void test_an_unknown_event_is_rejected() throws Exception {
        install("noop", "export default (d) => { };", TriggerDefinition.TIMING_BEFORE);
        final var response = test("v", "EXPLODED", document("x11"));
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("EXPLODED"));
    }

    @Test
    public void test_an_event_the_trigger_does_not_watch_is_rejected() throws Exception {
        install("noop", "export default (d) => { };", TriggerDefinition.TIMING_BEFORE);
        final var response = test("v", "DELETED", document("x12"));
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("DELETED"));
    }

    @Test
    public void test_an_unknown_collection_is_rejected() {
        final var response = processor
                .processMessage(new TestTriggerRequest(TestGlobals.DB, "missingColl", "v", "CREATED", document("x13")));
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_scripts_disabled_refuses_the_operation() throws Exception {
        install("noop", "export default (d) => { };", TriggerDefinition.TIMING_BEFORE);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(), test("v", "CREATED", document("x14")).getErrorCode());
    }
}
