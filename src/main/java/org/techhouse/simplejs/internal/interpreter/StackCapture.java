package org.techhouse.simplejs.internal.interpreter;

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
 */
public final class StackCapture {
    public static final int MAX_FRAMES = 32;

    private static final ThreadLocal<CallStack> CURRENT = new InheritableThreadLocal<>();

    private StackCapture() {
    }

    public static CallStack install(CallStack stack) {
        final var previous = CURRENT.get();
        CURRENT.set(stack);
        return previous;
    }

    public static void restore(CallStack previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public static List<String> current() {
        final var stack = CURRENT.get();
        return stack == null ? List.of() : stack.capture(MAX_FRAMES);
    }
}
