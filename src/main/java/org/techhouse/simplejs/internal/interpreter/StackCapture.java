package org.techhouse.simplejs.internal.interpreter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Reaches the running interpreter's {@link CallStack} from the static places an error is built.
 *
 * <p>
 * The trace has to be taken when the error is constructed: a runtime error becomes a JS object only at the
 * catch site, by which point every frame has already been unwound. Inheritable because a coroutine body runs
 * on its own virtual thread, which is created after the run installs its stack. Unlike the intrinsics
 * thread-local next to it in ErrorBuiltins this one is cleared when the run ends, since connection threads are
 * reused and a retained interpreter would both leak and mislabel the next run.
 *
 * <p>
 * The installed stacks are a per-thread deque rather than a single value plus a saved predecessor, because
 * several interpreters can be open on one thread at once (a pipeline or a before-hook context holds a callable
 * per script) and they are closed in map order, not in the order they were opened. Restoring a saved
 * predecessor would then reinstate a dead stack and leave it on the thread for good; removing the closed stack
 * from the deque is order-independent.
 */
public final class StackCapture {
    public static final int MAX_FRAMES = 32;

    private static final ThreadLocal<Deque<CallStack>> INSTALLED = new InheritableThreadLocal<>() {
        @Override
        protected Deque<CallStack> childValue(Deque<CallStack> parentValue) {
            return parentValue == null ? null : new ArrayDeque<>(parentValue);
        }
    };

    private StackCapture() {
    }

    public static void install(CallStack stack) {
        var installed = INSTALLED.get();
        if (installed == null) {
            installed = new ArrayDeque<>();
            INSTALLED.set(installed);
        }
        installed.addLast(stack);
    }

    public static void uninstall(CallStack stack) {
        final var installed = INSTALLED.get();
        if (installed == null) {
            return;
        }
        installed.removeLastOccurrence(stack);
        if (installed.isEmpty()) {
            INSTALLED.remove();
        }
    }

    public static List<String> current() {
        final var installed = INSTALLED.get();
        final var stack = installed == null ? null : installed.peekLast();
        return stack == null ? List.of() : stack.capture(MAX_FRAMES);
    }
}
