package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class StackTraceProgramTest {
    private final SimpleJs engine = new SimpleJs();

    private ScriptResult run(String source) {
        return engine.run(source, SimpleHostBindings.empty());
    }

    private static void assertFrameMatches(List<String> frames, int index, String name, int line) {
        final var frame = frames.get(index);
        assertTrue(frame.startsWith(name + " ("), () -> "frame " + index + " was " + frame);
        assertTrue(frame.contains(":" + line + ":"), () -> "frame " + index + " was " + frame);
    }

    @Test
    public void test_throw_in_a_nested_function_names_each_frame() {
        final var result = run("""
                function inner() {
                  throw new Error('boom');
                }
                function outer() {
                  inner();
                }
                outer();
                """);
        assertTrue(result.isError());
        final var frames = result.getErrorStack();
        assertEquals(3, frames.size());
        assertFrameMatches(frames, 0, "inner", 2);
        assertFrameMatches(frames, 1, "outer", 5);
        assertEquals("main:7:1", frames.get(2));
    }

    @Test
    public void test_runtime_type_error_carries_a_stack() {
        final var result = run("""
                function read() {
                  const x = null;
                  return x.total;
                }
                read();
                """);
        assertEquals("TypeError", result.getErrorName());
        assertFrameMatches(result.getErrorStack(), 0, "read", 3);
    }

    @Test
    public void test_statement_position_advances_within_a_function() {
        final var first = run("""
                function f(flag) {
                  if (flag) { throw new Error('a'); }
                  throw new Error('b');
                }
                f(true);
                """);
        final var second = run("""
                function f(flag) {
                  if (flag) { throw new Error('a'); }
                  throw new Error('b');
                }
                f(false);
                """);
        assertNotEquals(first.getErrorStack().getFirst(), second.getErrorStack().getFirst());
        assertTrue(second.getErrorStack().getFirst().contains(":3:"), second.getErrorStack()::toString);
    }

    // V8 captures at construction, so a later call must not rewrite an error's own trace
    @Test
    public void test_stack_reflects_the_construction_site() {
        final var result = run("""
                const e = new Error('made here');
                function later() {
                  return 1;
                }
                later();
                throw e;
                """);
        assertTrue(result.getErrorStack().getFirst().startsWith("main:1:"), result.getErrorStack()::toString);
    }

    // A function expression assigned to a binding takes that binding's name, so a truly anonymous frame
    // needs a callee the spec never names
    @Test
    public void test_anonymous_frame_is_rendered() {
        final var result = run("""
                (function () {
                  throw new Error('x');
                })();
                """);
        assertTrue(result.getErrorStack().getFirst().startsWith("<anonymous> ("), result.getErrorStack()::toString);
    }

    // A suspended generator's frame must not appear in the trace of whatever resumed it
    @Test
    public void test_suspended_generator_frame_is_not_visible_to_its_consumer() {
        final var result = run("""
                function* items() {
                  yield 1;
                  yield 2;
                }
                for (const item of items()) {
                  throw new Error('consumer');
                }
                """);
        assertTrue(result.isError());
        final var frames = result.getErrorStack();
        assertTrue(frames.stream().noneMatch(frame -> frame.startsWith("items (")), frames::toString);
    }

    @Test
    public void test_async_frame_after_an_await_is_captured() {
        final var result = run("""
                async function work() {
                  await Promise.resolve(1);
                  throw new Error('after await');
                }
                return work();
                """);
        assertTrue(result.isError());
        assertFrameMatches(result.getErrorStack(), 0, "work", 3);
    }

    @Test
    public void test_method_frame_uses_the_method_name() {
        final var result = run("""
                class Repo {
                  load() {
                    throw new Error('x');
                  }
                }
                new Repo().load();
                """);
        assertFrameMatches(result.getErrorStack(), 0, "load", 3);
    }

    @Test
    public void test_stack_survives_catch_and_rethrow() {
        final var result = run("""
                function thrower() {
                  throw new Error('original');
                }
                try {
                  thrower();
                } catch (e) {
                  throw e;
                }
                """);
        assertFrameMatches(result.getErrorStack(), 0, "thrower", 2);
    }

    @Test
    public void test_async_function_frame_is_captured() {
        final var result = run("""
                async function work() {
                  throw new Error('async boom');
                }
                return work();
                """);
        assertTrue(result.isError());
        assertFrameMatches(result.getErrorStack(), 0, "work", 2);
    }

    @Test
    public void test_generator_frame_is_captured() {
        final var result = run("""
                function* items() {
                  throw new Error('gen boom');
                }
                for (const item of items()) {
                  item;
                }
                """);
        assertTrue(result.isError());
        assertFrameMatches(result.getErrorStack(), 0, "items", 2);
    }

    @Test
    public void test_top_level_throw_has_no_function_frame() {
        final var result = run("throw new Error('top');");
        assertEquals(List.of("main:1:1"), result.getErrorStack());
    }

    // A sandbox abort is not a program error, so it deliberately reports no frames
    @Test
    public void test_limit_abort_carries_no_stack() {
        final var host = new SimpleHostBindings(new JsonObject(), null, null, new ResourceLimits(2000, 5000, 20));
        final var result = engine.run("function f() { while (true) {} } f();", host);
        assertEquals("ScriptLimitError", result.getErrorName());
        assertTrue(result.getErrorStack() == null || result.getErrorStack().isEmpty());
    }

    @Test
    public void test_depth_abort_carries_no_stack() {
        final var host = new SimpleHostBindings(new JsonObject(), null, null, new ResourceLimits(1000000, 5000, 10));
        final var result = engine.run("function f() { return f(); } f();", host);
        assertEquals("ScriptLimitError", result.getErrorName());
        assertTrue(result.getErrorStack() == null || result.getErrorStack().isEmpty());
    }

    @Test
    public void test_deep_recursion_truncates_the_capture() {
        final var host = new SimpleHostBindings(new JsonObject(), null, null, new ResourceLimits(1000000, 5000, 60));
        final var result = engine.run("""
                function down(n) {
                  if (n === 0) { throw new Error('bottom'); }
                  return down(n - 1);
                }
                down(50);
                """, host);
        assertTrue(result.isError());
        assertTrue(result.getErrorStack().getLast().startsWith("... "), result.getErrorStack()::toString);
    }

    // Two runs on different threads must not share or interleave their stacks
    @Test
    public void test_concurrent_runs_keep_separate_stacks() throws Exception {
        final var ready = new CountDownLatch(2);
        final var first = new AtomicReference<List<String>>();
        final var second = new AtomicReference<List<String>>();
        final Runnable one = () -> {
            first.set(new SimpleJs()
                    .run("function alpha() { throw new Error('a'); } alpha();", SimpleHostBindings.empty())
                    .getErrorStack());
            ready.countDown();
        };
        final Runnable two = () -> {
            second.set(
                    new SimpleJs().run("function beta() { throw new Error('b'); } beta();", SimpleHostBindings.empty())
                            .getErrorStack());
            ready.countDown();
        };
        Thread.ofVirtual().start(one);
        Thread.ofVirtual().start(two);
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        assertTrue(first.get().getFirst().startsWith("alpha ("), first.get()::toString);
        assertTrue(second.get().getFirst().startsWith("beta ("), second.get()::toString);
    }

    // The engine's own `stack` accessor renders the same frames
    @Test
    public void test_error_stack_property_renders_frames() {
        final var result = run("""
                function fails() {
                  throw new Error('x');
                }
                try {
                  fails();
                } catch (e) {
                  return e.stack;
                }
                """);
        final var stack = result.getValue().asJsonString().getValue();
        assertTrue(stack.startsWith("Error: x\n    at fails ("), stack);
    }
}
