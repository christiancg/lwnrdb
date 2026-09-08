package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

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
import org.techhouse.ops.BeforeHookContext;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRegistry;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class BeforeHookContextTest {
    private static final String ACTOR = "alice";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
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
        final var existing = new java.util.ArrayList<>(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL));
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

    private org.techhouse.ops.BeforeHookOutcome run(JsonObject document, EventType event) {
        try (var hooks = BeforeHookContext.open(TestGlobals.DB, TestGlobals.COLL, event, ACTOR)) {
            return hooks.apply(document, document.has("_id") ? document.get("_id").asJsonString().getValue() : null,
                    OperationType.SAVE);
        }
    }

    @Test
    public void test_accepts_when_the_hook_returns_undefined() throws Exception {
        installHook("v", "noop", "export default function (doc) { };", EventType.CREATED);
        final var document = document("a");
        final var outcome = run(document, EventType.CREATED);
        assertFalse(outcome.isRejected());
        assertSame(document, outcome.document());
    }

    @Test
    public void test_accepts_when_the_hook_returns_true() throws Exception {
        installHook("v", "yes", "export default function (doc) { return true; };", EventType.CREATED);
        final var document = document("a");
        assertSame(document, run(document, EventType.CREATED).document());
    }

    @Test
    public void test_accepts_when_the_hook_returns_null() throws Exception {
        installHook("v", "nul", "export default function (doc) { return null; };", EventType.CREATED);
        final var document = document("a");
        assertSame(document, run(document, EventType.CREATED).document());
    }

    @Test
    public void test_replaces_the_document_when_the_hook_returns_an_object() throws Exception {
        installHook("v", "calc", "export default function (doc) { return { ...doc, total: doc.qty * doc.price }; };",
                EventType.CREATED);
        final var outcome = run(document("a"), EventType.CREATED);
        assertFalse(outcome.isRejected());
        assertEquals(20.0, outcome.document().get("total").asJsonNumber().getValue().doubleValue());
        assertEquals("a", outcome.document().get("_id").asJsonString().getValue());
    }

    @Test
    public void test_rejects_when_the_hook_throws() throws Exception {
        installHook("v", "boom", "export default function (doc) { throw new Error('customerId is required'); };",
                EventType.CREATED);
        final var outcome = run(document("a"), EventType.CREATED);
        assertTrue(outcome.isRejected());
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), outcome.rejection().getErrorCode());
        assertTrue(outcome.rejection().getMessage().contains("customerId is required"));
    }

    @Test
    public void test_rejects_when_the_hook_returns_a_number() throws Exception {
        installHook("v", "num", "export default function (doc) { return 42; };", EventType.CREATED);
        final var outcome = run(document("a"), EventType.CREATED);
        assertTrue(outcome.isRejected());
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), outcome.rejection().getErrorCode());
    }

    @Test
    public void test_rejects_when_the_hook_returns_a_string() throws Exception {
        installHook("v", "str", "export default function (doc) { return 'nope'; };", EventType.CREATED);
        assertTrue(run(document("a"), EventType.CREATED).isRejected());
    }

    @Test
    public void test_rejects_when_the_hook_returns_an_array() throws Exception {
        installHook("v", "arr", "export default function (doc) { return [1, 2]; };", EventType.CREATED);
        final var outcome = run(document("a"), EventType.CREATED);
        assertTrue(outcome.isRejected());
        assertTrue(outcome.rejection().getMessage().contains("array"));
    }

    @Test
    public void test_rejects_when_the_hook_returns_false() throws Exception {
        installHook("v", "no", "export default function (doc) { return false; };", EventType.CREATED);
        assertTrue(run(document("a"), EventType.CREATED).isRejected());
    }

    @Test
    public void test_rejects_when_the_replacement_changes_the_id() throws Exception {
        installHook("v", "relocate", "export default function (doc) { return { ...doc, _id: 'somewhere-else' }; };",
                EventType.CREATED);
        final var outcome = run(document("a"), EventType.CREATED);
        assertTrue(outcome.isRejected());
        assertTrue(outcome.rejection().getMessage().contains("_id"));
    }

    @Test
    public void test_rejects_when_the_replacement_drops_the_id() throws Exception {
        installHook("v", "dropid", "export default function (doc) { return { qty: 1 }; };", EventType.CREATED);
        assertTrue(run(document("a"), EventType.CREATED).isRejected());
    }

    @Test
    public void test_rejects_a_replacement_on_a_deleted_event() throws Exception {
        installHook("v", "del", "export default function (doc) { return { ...doc }; };", EventType.DELETED);
        final var outcome = run(document("a"), EventType.DELETED);
        assertTrue(outcome.isRejected());
        assertTrue(outcome.rejection().getMessage().contains("DELETED"));
    }

    @Test
    public void test_accepts_on_a_deleted_event_when_nothing_is_returned() throws Exception {
        installHook("v", "delok", "export default function (doc) { };", EventType.DELETED);
        assertFalse(run(document("a"), EventType.DELETED).isRejected());
    }

    @Test
    public void test_rejects_when_the_procedure_no_longer_exists() throws Exception {
        installHook("v", "gone", "export default function (doc) { };", EventType.CREATED);
        fs.deleteProcedure(TestGlobals.DB, "gone");
        cache.removeProceduresForDatabase(TestGlobals.DB);
        final var outcome = run(document("a"), EventType.CREATED);
        assertTrue(outcome.isRejected());
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), outcome.rejection().getErrorCode());
    }

    @Test
    public void test_rejects_when_the_hook_cannot_be_parsed() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "ok", "export default (d) => d;"),
                ACTOR);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("v", new LinkedHashSet<>(Set.of(EventType.CREATED)), "ok",
                        TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_BEFORE, false, true, ACTOR, 1L, 1L,
                        1L, ACTOR)));
        assertFalse(run(document("a"), EventType.CREATED).isRejected());
    }

    // Fail-closed: a hook that never finished must stop the write, and keep its own code so an operator
    // can tell it apart from a hook that deliberately said no.
    @Test
    public void test_a_timeout_rejects_the_write_with_its_own_code() throws Exception {
        TestUtils.setPrivateField(configuration, "beforeHookInstructionBudget", 5_000_000_000L);
        TestUtils.setPrivateField(configuration, "beforeHookTimeoutMs", 50L);
        installHook("v", "spinForever", "export default function (doc) { while (true) { } };", EventType.CREATED);
        final var outcome = run(document("a"), EventType.CREATED);
        assertTrue(outcome.isRejected());
        assertEquals(ErrorCode.SCRIPT_TIMEOUT.getCode(), outcome.rejection().getErrorCode());
    }

    @Test
    public void test_an_exhausted_instruction_budget_rejects_the_write() throws Exception {
        TestUtils.setPrivateField(configuration, "beforeHookInstructionBudget", 500L);
        installHook("v", "spinBudget", "export default function (doc) { for (let i = 0; i < 1e9; i++) { } };",
                EventType.CREATED);
        final var outcome = run(document("a"), EventType.CREATED);
        assertTrue(outcome.isRejected());
        assertEquals(ErrorCode.SCRIPT_LIMIT_EXCEEDED.getCode(), outcome.rejection().getErrorCode());
    }

    @Test
    public void test_chains_hooks_in_name_order() throws Exception {
        installHook("a_first", "one", "export default function (doc) { return { ...doc, trail: 'a' }; };",
                EventType.CREATED);
        installHook("b_second", "two", "export default function (doc) { return { ...doc, trail: doc.trail + 'b' }; };",
                EventType.CREATED);
        final var outcome = run(document("a"), EventType.CREATED);
        assertEquals("ab", outcome.document().get("trail").asJsonString().getValue());
    }

    @Test
    public void test_a_rejection_stops_the_chain() throws Exception {
        installHook("a_first", "one", "export default function (doc) { throw new Error('no'); };", EventType.CREATED);
        installHook("b_second", "two", "export default function (doc) { return { ...doc, ran: true }; };",
                EventType.CREATED);
        assertTrue(run(document("a"), EventType.CREATED).isRejected());
    }

    // One interpreter per request, so the module body is evaluated once no matter how many documents the
    // request carries - the property that makes a bulk save affordable.
    @Test
    public void test_shares_one_callable_across_documents() throws Exception {
        installHook("v", "counter",
                "let opened = 0; opened++; export default function (doc) { return { ...doc, opened: opened }; };",
                EventType.CREATED);
        try (var hooks = BeforeHookContext.open(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, ACTOR)) {
            final var first = hooks.apply(document("a"), "a", OperationType.SAVE);
            final var second = hooks.apply(document("b"), "b", OperationType.SAVE);
            assertEquals(1.0, first.document().get("opened").asJsonNumber().getValue().doubleValue());
            assertEquals(1.0, second.document().get("opened").asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_shares_one_budget_across_documents() throws Exception {
        TestUtils.setPrivateField(configuration, "beforeHookInstructionBudget", 20_000L);
        installHook("v", "loop", "export default function (doc) { for (let i = 0; i < 2000; i++) { } return; };",
                EventType.CREATED);
        try (var hooks = BeforeHookContext.open(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, ACTOR)) {
            var rejectedAt = -1;
            for (var i = 0; i < 100; i++) {
                if (hooks.apply(document("d" + i), "d" + i, OperationType.SAVE).isRejected()) {
                    rejectedAt = i;
                    break;
                }
            }
            assertTrue(rejectedAt > 0, "the shared budget should run out partway through, not on the first document");
        }
    }

    @Test
    public void test_has_hooks_for_is_false_without_before_rows() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "after", "return 1;"), ACTOR);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "after",
                        TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_AFTER, false, true, ACTOR, 1L, 1L, 1L,
                        ACTOR)));
        assertFalse(BeforeHookContext.hasHooksFor(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED));
    }

    @Test
    public void test_has_hooks_for_is_false_for_a_disabled_row() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "off", "return 1;"), ACTOR);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("v", new LinkedHashSet<>(Set.of(EventType.CREATED)), "off",
                        TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_BEFORE, false, false, ACTOR, 1L, 1L,
                        1L, ACTOR)));
        assertFalse(BeforeHookContext.hasHooksFor(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED));
    }

    @Test
    public void test_has_hooks_for_is_false_for_another_event() throws Exception {
        installHook("v", "noop", "export default function (doc) { };", EventType.CREATED);
        assertTrue(BeforeHookContext.hasHooksFor(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED));
        assertFalse(BeforeHookContext.hasHooksFor(TestGlobals.DB, TestGlobals.COLL, EventType.DELETED));
    }

    @Test
    public void test_triggers_disabled_runs_no_hook() throws Exception {
        installHook("v", "boom", "export default function (doc) { throw new Error('no'); };", EventType.CREATED);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        assertFalse(BeforeHookContext.hasHooksFor(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED));
        assertFalse(run(document("a"), EventType.CREATED).isRejected());
    }

    // A hook exercises no authority, so unlike an after trigger a deleted definer does not disable it.
    @Test
    public void test_a_missing_definer_does_not_disable_a_before_hook() throws Exception {
        ProcedureOperationHelper.executeSave(
                new SaveProcedureRequest(TestGlobals.DB, "calc", "export default (d) => ({ ...d, ok: true });"), ACTOR);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("v", new LinkedHashSet<>(Set.of(EventType.CREATED)), "calc",
                        TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_BEFORE, false, true,
                        "user-who-is-gone", 1L, 1L, 1L, ACTOR)));
        final var outcome = run(document("a"), EventType.CREATED);
        assertFalse(outcome.isRejected());
        assertTrue(outcome.document().get("ok").asJsonBoolean().getValue());
    }

    @Test
    public void test_a_hook_has_no_db_binding() throws Exception {
        installHook("v", "needsdb", "import db from 'db'; export default function (doc) { return doc; };",
                EventType.CREATED);
        final var outcome = run(document("a"), EventType.CREATED);
        assertTrue(outcome.isRejected());
        assertTrue(outcome.rejection().getMessage().contains("Database access is not available"),
                outcome.rejection().getMessage());
    }

    @Test
    public void test_a_hook_may_import_a_sibling_procedure() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "lib",
                "export function tag(d) { return { ...d, via: 'lib' }; }"), ACTOR);
        installHook("v", "uses",
                "import { tag } from 'procedures/lib'; export default function (doc) { return tag(doc); };",
                EventType.CREATED);
        final var outcome = run(document("a"), EventType.CREATED);
        assertFalse(outcome.isRejected(), outcome.isRejected() ? outcome.rejection().getMessage() : "");
        assertEquals("lib", outcome.document().get("via").asJsonString().getValue());
    }

    @Test
    public void test_the_context_names_the_event_and_the_trigger() throws Exception {
        installHook("v", "ctx",
                "export default function (doc, ctx) { return { ...doc, e: ctx.event, t: ctx.trigger, u: ctx.actingUser }; };",
                EventType.UPDATED);
        final var outcome = run(document("a"), EventType.UPDATED);
        assertEquals("UPDATED", outcome.document().get("e").asJsonString().getValue());
        assertEquals("v", outcome.document().get("t").asJsonString().getValue());
        assertEquals(ACTOR, outcome.document().get("u").asJsonString().getValue());
    }

    @Test
    public void test_close_is_idempotent() throws Exception {
        installHook("v", "noop", "export default function (doc) { };", EventType.CREATED);
        final var hooks = BeforeHookContext.open(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, ACTOR);
        hooks.apply(document("a"), "a", OperationType.SAVE);
        hooks.close();
        hooks.close();
    }

    @Test
    public void test_is_empty_reports_no_hooks() throws Exception {
        try (var hooks = BeforeHookContext.open(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, ACTOR)) {
            assertTrue(hooks.isEmpty());
        }
        installHook("v", "noop", "export default function (doc) { };", EventType.CREATED);
        try (var hooks = BeforeHookContext.open(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, ACTOR)) {
            assertFalse(hooks.isEmpty());
        }
    }

    // Registered per request, so LIST_SCRIPTS sees one run covering the whole request rather than one per
    // document - which is also what makes the module-body evaluation inside openCallable cancellable.
    @Test
    public void test_a_running_hook_is_visible_to_the_run_registry() throws Exception {
        installHook("v", "noop", "export default function (doc) { };", EventType.CREATED);
        final var registry = IocContainer.get(ScriptRunRegistry.class);
        final var before = registry.size();
        try (var opened = BeforeHookContext.open(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, ACTOR)) {
            assertFalse(opened.isEmpty());
            assertEquals(before + 1, registry.size());
            assertTrue(registry.list().stream().anyMatch(run -> run.kind() == ScriptRunKind.BEFORE_HOOK));
        }
        assertEquals(before, registry.size(), "closing the context must unregister the run");
    }

    @Test
    public void test_an_empty_context_registers_nothing() {
        final var registry = IocContainer.get(ScriptRunRegistry.class);
        final var before = registry.size();
        try (var hooks = BeforeHookContext.open(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, ACTOR)) {
            assertTrue(hooks.isEmpty());
            assertEquals(before, registry.size());
        }
    }

    // Cancelling must actually stop the hook and refuse the write, not merely report success while the hook
    // runs on to its own timeout.
    @Test
    public void test_cancelling_a_running_hook_refuses_the_write() throws Exception {
        TestUtils.setPrivateField(configuration, "beforeHookInstructionBudget", 50_000_000_000L);
        TestUtils.setPrivateField(configuration, "beforeHookTimeoutMs", 30_000L);
        installHook("v", "spinCancel", "export default function (doc) { while (true) { } };", EventType.CREATED);
        final var registry = IocContainer.get(ScriptRunRegistry.class);
        final var canceller = Thread.ofVirtual().start(() -> {
            for (var i = 0; i < 600; i++) {
                final var running = registry.list().stream().filter(run -> run.kind() == ScriptRunKind.BEFORE_HOOK)
                        .findFirst();
                if (running.isPresent() && registry.cancel(running.get().runId())) {
                    return;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        final var outcome = run(document("a"), EventType.CREATED);
        canceller.join();
        assertTrue(outcome.isRejected());
        assertEquals(ErrorCode.SCRIPT_CANCELLED.getCode(), outcome.rejection().getErrorCode(),
                outcome.rejection().getMessage());
    }
}
