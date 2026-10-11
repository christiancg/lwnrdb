package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.BeforeHookContext;
import org.techhouse.ops.BulkBeforeHooks;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ScriptRunRegistry;
import org.techhouse.test.TestGlobals;

public class BulkBeforeHooksTest {
    private final ScriptRunRegistry runRegistry = IocContainer.get(ScriptRunRegistry.class);

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
    }

    @Test
    public void test_no_hooks_accepts_the_document_unchanged() {
        final var document = BeforeHookTestSupport.document("h1");
        try (var hooks = BulkBeforeHooks.open(TestGlobals.DB, TestGlobals.COLL, BeforeHookTestSupport.ACTOR)) {
            final var created = hooks.apply(false, document, "h1", OperationType.BULK_SAVE);
            final var updated = hooks.apply(true, document, "h1", OperationType.BULK_SAVE);
            assertFalse(created.isRejected());
            assertSame(document, created.document());
            assertSame(document, updated.document());
        }
    }

    @Test
    public void test_apply_routes_by_insert_or_update() throws Exception {
        BeforeHookTestSupport.installHook("onCreate", "oncreate", "export default (d) => ({ ...d, via: 'create' });",
                EventType.CREATED);
        BeforeHookTestSupport.installHook("onUpdate", "onupdate", "export default (d) => ({ ...d, via: 'update' });",
                EventType.UPDATED);
        try (var hooks = BulkBeforeHooks.open(TestGlobals.DB, TestGlobals.COLL, BeforeHookTestSupport.ACTOR)) {
            final var created = hooks.apply(false, BeforeHookTestSupport.document("h2"), "h2", OperationType.BULK_SAVE);
            final var updated = hooks.apply(true, BeforeHookTestSupport.document("h3"), "h3", OperationType.BULK_SAVE);
            assertEquals("create", created.document().get("via").asJsonString().getValue());
            assertEquals("update", updated.document().get("via").asJsonString().getValue());
        }
    }

    @Test
    public void test_close_unregisters_both_runs() throws Exception {
        BeforeHookTestSupport.installHook("both", "both", "export default (d) => d;", EventType.CREATED,
                EventType.UPDATED);
        final var before = runRegistry.size();
        final var hooks = BulkBeforeHooks.open(TestGlobals.DB, TestGlobals.COLL, BeforeHookTestSupport.ACTOR);
        assertEquals(before + 2, runRegistry.size());

        hooks.close();

        assertEquals(before, runRegistry.size());
    }

    @Test
    @SuppressWarnings("resource")
    public void test_a_failed_open_closes_the_context_it_already_opened() throws Exception {
        BeforeHookTestSupport.installHook("both", "both", "export default (d) => d;", EventType.CREATED,
                EventType.UPDATED);
        final var before = runRegistry.size();
        try (var contexts = mockStatic(BeforeHookContext.class, CALLS_REAL_METHODS)) {
            contexts.when(() -> BeforeHookContext.open(anyString(), anyString(), eq(EventType.UPDATED), any()))
                    .thenThrow(new IllegalStateException("unreadable triggers"));

            assertThrows(IllegalStateException.class,
                    () -> BulkBeforeHooks.open(TestGlobals.DB, TestGlobals.COLL, BeforeHookTestSupport.ACTOR));
        }
        assertEquals(before, runRegistry.size());
    }
}
