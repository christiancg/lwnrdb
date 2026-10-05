package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.BeforeHookContext;
import org.techhouse.ops.BeforeHookOutcome;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.DeleteSchemaRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.SaveSchemaRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.req.validations.DataRequestValidator;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.utils.JsonUtils;

public class BeforeHookReplacementDepthTest {
    private static final int LEVELS_ABOVE_THE_NESTED_VALUE = 2;
    private static final int DEEPEST_ACCEPTED_WRAPS = Globals.MAX_REQUEST_NESTING_DEPTH - LEVELS_ABOVE_THE_NESTED_VALUE;

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        BeforeHookTestSupport.setUpAll();
    }

    @AfterAll
    static void tearDown() throws Exception {
        BeforeHookTestSupport.tearDownAll();
    }

    @BeforeEach
    void reset() throws Exception {
        BeforeHookTestSupport.reset();
        processor.processMessage(new DeleteSchemaRequest(TestGlobals.DB, TestGlobals.COLL));
    }

    private static void installNestingHook(int wraps) throws Exception {
        BeforeHookTestSupport.installHook("nest", "nest",
                "export default (d) => { let o = {}; for (let i = 0; i < " + wraps
                        + "; i++) { o = { a: o }; } return { ...d, deep: o }; };",
                EventType.CREATED, EventType.UPDATED);
    }

    private static BeforeHookOutcome run(String id) {
        try (var hooks = BeforeHookContext.open(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                BeforeHookTestSupport.ACTOR)) {
            return hooks.apply(BeforeHookTestSupport.document(id), id, OperationType.SAVE);
        }
    }

    private JsonObject find(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request) instanceof FindByIdResponse found ? found.getObject() : null;
    }

    @Test
    public void test_rejects_a_replacement_deeper_than_the_request_cap() throws Exception {
        installNestingHook(200);

        final var outcome = run("deep");

        assertTrue(outcome.isRejected());
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), outcome.rejection().getErrorCode());
        assertTrue(outcome.rejection().getMessage().contains(DataRequestValidator.NESTING_MESSAGE),
                outcome.rejection().getMessage());
    }

    @Test
    public void test_accepts_a_replacement_at_the_deepest_level_a_request_may_have() throws Exception {
        installNestingHook(DEEPEST_ACCEPTED_WRAPS);

        final var outcome = run("edge");

        assertFalse(outcome.isRejected());
        assertFalse(JsonUtils.nestingExceeds(outcome.document(), Globals.MAX_REQUEST_NESTING_DEPTH));
        assertTrue(JsonUtils.nestingExceeds(outcome.document(), Globals.MAX_REQUEST_NESTING_DEPTH - 1),
                "the accepted replacement must sit exactly at the cap, or this does not pin the boundary");
    }

    @Test
    public void test_one_level_past_the_cap_is_rejected() throws Exception {
        installNestingHook(DEEPEST_ACCEPTED_WRAPS + 1);

        assertTrue(run("past").isRejected());
    }

    @Test
    public void test_a_too_deep_replacement_is_refused_before_the_schema_runs() throws Exception {
        processor.processMessage(new SaveSchemaRequest(TestGlobals.DB, TestGlobals.COLL,
                eJson.fromJson("{\"type\":\"object\",\"required\":[\"absent\"]}", JsonObject.class)));
        installNestingHook(200);

        final var message = run("schema").rejection().getMessage();

        assertTrue(message.contains(DataRequestValidator.NESTING_MESSAGE), message);
        assertFalse(message.contains("schema"), message);
    }

    @Test
    public void test_a_too_deep_replacement_is_refused_inside_a_transaction() throws Exception {
        installNestingHook(200);
        final var client = BeforeHookTestSupport.newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(BeforeHookTestSupport.document("tx-deep"));
        request.set_id("tx-deep");

        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(),
                processor.processMessage(request, client).getErrorCode());
        processor.processMessage(new CommitTransactionRequest(), client);
        assertNull(find("tx-deep"));
    }

    @Test
    public void test_a_too_deep_replacement_is_refused_standalone() throws Exception {
        installNestingHook(200);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(BeforeHookTestSupport.document("solo-deep"));
        request.set_id("solo-deep");

        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), processor.processMessage(request).getErrorCode());
        assertNull(find("solo-deep"));
    }
}
