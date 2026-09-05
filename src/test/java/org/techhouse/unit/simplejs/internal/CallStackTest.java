package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.internal.interpreter.CallStack;
import org.techhouse.simplejs.internal.interpreter.StackCapture;

public class CallStackTest {
    private static SourcePosition at(int line, int column) {
        return new SourcePosition(0, 1, line, column);
    }

    // The innermost frame comes first, then each caller's own call site
    @Test
    public void test_captures_innermost_position_first() {
        final var stack = new CallStack();
        stack.setPosition(at(1, 1));
        stack.push("outer", "main");
        stack.setPosition(at(5, 3));
        stack.push("inner", "main");
        stack.setPosition(at(9, 7));

        final var frames = stack.capture(32);
        assertEquals(3, frames.size());
        assertEquals("inner (main:9:7)", frames.get(0));
        assertEquals("outer (main:5:3)", frames.get(1));
        assertEquals("main:1:1", frames.get(2));
    }

    @Test
    public void test_restores_caller_position_on_pop() {
        final var stack = new CallStack();
        stack.setPosition(at(4, 2));
        stack.push("f", "main");
        stack.setPosition(at(20, 1));
        stack.pop();

        assertEquals("main:4:2", stack.capture(32).getFirst());
    }

    @Test
    public void test_truncates_beyond_max_frames() {
        final var stack = new CallStack();
        for (var i = 0; i < 40; i++) {
            stack.push("f" + i, "main");
        }
        final var frames = stack.capture(32);
        assertEquals(33, frames.size());
        assertEquals("... 9 more frames", frames.getLast());
    }

    @Test
    public void test_single_dropped_frame_is_singular() {
        final var stack = new CallStack();
        stack.push("a", "main");
        stack.push("b", "main");
        final var frames = stack.capture(2);
        assertEquals("... 1 more frame", frames.getLast());
    }

    @Test
    public void test_renders_module_qualified_frames() {
        final var stack = new CallStack();
        final var previous = stack.enterModule("procedures/lib");
        stack.push("helper", "procedures/lib");
        stack.setPosition(at(3, 5));

        assertEquals("helper (procedures/lib:3:5)", stack.capture(32).getFirst());
        stack.pop();
        stack.exitModule(previous);
        assertEquals(CallStack.TOP_LEVEL_MODULE, stack.currentModule());
    }

    // A function defined in one module and called from another keeps its own module label
    @Test
    public void test_frame_uses_the_pushed_module_not_the_current_one() {
        final var stack = new CallStack();
        stack.push("imported", "procedures/lib");
        stack.setPosition(at(2, 1));
        assertEquals("imported (procedures/lib:2:1)", stack.capture(32).getFirst());
    }

    @Test
    public void test_anonymous_function_is_named() {
        final var stack = new CallStack();
        stack.push(null, "main");
        assertTrue(stack.capture(32).getFirst().startsWith(CallStack.ANONYMOUS));
        stack.pop();
        stack.push("", "main");
        assertTrue(stack.capture(32).getFirst().startsWith(CallStack.ANONYMOUS));
    }

    // A fresh stack still reports where it is, rather than nothing at all
    @Test
    public void test_fresh_stack_reports_the_top_level() {
        final var frames = new CallStack().capture(32);
        assertNotNull(frames);
        assertEquals(1, frames.size());
        assertEquals(CallStack.TOP_LEVEL_MODULE, frames.getFirst());
    }

    // A position is only rendered once one is known, so an unstamped node leaves the module bare
    @Test
    public void test_null_position_is_ignored() {
        final var stack = new CallStack();
        stack.setPosition(at(7, 2));
        stack.setPosition(null);
        assertEquals("main:7:2", stack.capture(32).getFirst());
    }

    @Test
    public void test_pop_on_an_empty_stack_is_a_no_op() {
        final var stack = new CallStack();
        stack.pop();
        assertEquals(1, stack.capture(32).size());
    }

    @Test
    public void test_negative_max_frames_captures_everything() {
        final var stack = new CallStack();
        for (var i = 0; i < 40; i++) {
            stack.push("f" + i, "main");
        }
        assertEquals(41, stack.capture(-1).size());
    }

    @Test
    public void test_capture_without_an_installed_stack_is_empty() {
        assertTrue(StackCapture.current().isEmpty());
    }

    @Test
    public void test_install_and_restore_round_trip() {
        final var stack = new CallStack();
        stack.push("f", "main");
        final var previous = StackCapture.install(stack);
        try {
            assertFalse(StackCapture.current().isEmpty());
        } finally {
            StackCapture.restore(previous);
        }
        assertTrue(StackCapture.current().isEmpty());
    }
}
