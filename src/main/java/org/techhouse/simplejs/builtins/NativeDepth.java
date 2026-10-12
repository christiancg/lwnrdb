package org.techhouse.simplejs.builtins;

public final class NativeDepth {
    public static final int MAX = 512;
    public static final String EXCEEDED = "Maximum nesting depth exceeded";

    private NativeDepth() {
    }

    public static void enter(InterpreterOps ops) {
        if (ops != null) {
            ops.enterNested();
        }
    }

    public static void leave(InterpreterOps ops) {
        if (ops != null) {
            ops.leaveNested();
        }
    }
}
