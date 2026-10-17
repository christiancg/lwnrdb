package org.techhouse.simplejs.builtins;

import java.util.function.Supplier;

public final class NativeDepth {
    public static final int MAX = 256;
    public static final String EXCEEDED = "Maximum nesting depth exceeded";
    public static final int DB_CALL_RESERVE = 32;

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

    public static <T> T withHeadroom(InterpreterOps ops, Supplier<T> call) {
        requireHeadroom(ops);
        return call.get();
    }

    public static void requireHeadroom(InterpreterOps ops) {
        var entered = 0;
        try {
            while (entered < DB_CALL_RESERVE) {
                enter(ops);
                entered++;
            }
        } finally {
            for (var i = 0; i < entered; i++) {
                leave(ops);
            }
        }
    }
}
