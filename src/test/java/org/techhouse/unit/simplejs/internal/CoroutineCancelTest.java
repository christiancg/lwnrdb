package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsUndefined;

public class CoroutineCancelTest {
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);

    private record Bindings(ResourceLimits limits, Consumer<String> console) implements HostBindings {
        @Override
        public JsonObject args() {
            return new JsonObject();
        }

        @Override
        public DatabaseAccess database() {
            return null;
        }
    }

    @Test
    public void test_a_yield_in_finally_after_cancel_finishes_instead_of_parking() {
        final var coroutine = new Coroutine();
        final var cleanups = new AtomicInteger();
        coroutine.prime(() -> {
            try {
                coroutine.yieldOut(new JsNumber(1));
            } finally {
                cleanups.incrementAndGet();
                coroutine.yieldOut(new JsNumber(2));
            }
            return JsUndefined.getInstance();
        });
        coroutine.resumeNext(JsUndefined.getInstance());

        assertTimeoutPreemptively(Duration.ofSeconds(5), coroutine::cancel);

        assertTrue(coroutine.isDone());
        assertEquals(1, cleanups.get());
    }

    @Test
    public void test_a_cancelled_coroutine_cannot_be_resumed_into_another_yield() {
        final var coroutine = new Coroutine();
        coroutine.prime(() -> {
            coroutine.yieldOut(new JsNumber(1));
            coroutine.yieldOut(new JsNumber(2));
            return JsUndefined.getInstance();
        });
        coroutine.resumeNext(JsUndefined.getInstance());
        coroutine.cancel();

        assertTrue(coroutine.isDone());
        assertTrue(coroutine.resumeNext(JsUndefined.getInstance()).done());
    }

    @Test
    public void test_a_script_whose_generator_yields_in_finally_still_completes() {
        final var limits = new ResourceLimits(-1, 10_000L, 200, 8L * 1024 * 1024);
        final var bindings = new Bindings(limits, _ -> {
        });
        for (var i = 0; i < 20; i++) {
            final var result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> simpleJs.run(
                    "function* g() { try { yield 1; } finally { yield 99; } } const it = g(); it.next(); return 7;",
                    bindings));
            assertFalse(result.isError(), result.getErrorMessage());
            assertEquals(7, result.getValue().asJsonNumber().getValue().intValue());
        }
    }

    @Test
    public void test_a_generator_that_swallows_its_cancel_does_not_replace_the_finished_result() {
        final var limits = new ResourceLimits(-1, 300L, 200, 8L * 1024 * 1024);
        final var result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> simpleJs.run("function* spin() { for (;;) { try { yield 1; } finally { continue; } } }"
                        + " const s = spin(); s.next();"
                        + " function* g() { try { yield 1; } finally { yield 2; } } const t = g(); t.next();"
                        + " return 7;", new Bindings(limits, _ -> {
                        })));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(7, result.getValue().asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_an_async_function_awaiting_forever_in_finally_still_completes() {
        final var limits = new ResourceLimits(-1, 10_000L, 200, 8L * 1024 * 1024);
        final var result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> simpleJs.run(
                        "async function f() { try { await new Promise(() => {}); }"
                                + " finally { await new Promise(() => {}); } } f(); return 3;",
                        new Bindings(limits, _ -> {
                        })));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(3, result.getValue().asJsonNumber().getValue().intValue());
    }
}
