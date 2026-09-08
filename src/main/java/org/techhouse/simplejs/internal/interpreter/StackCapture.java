package org.techhouse.simplejs.internal.interpreter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

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
