package org.techhouse.simplejs.internal.interpreter;

// One entry of the interpreter's call stack, holding the caller's state rather than the callee's:
// entering a function saves where the call was made from, and leaving it restores that, so a frame
// is a return address in the ordinary sense.
final class CallFrame {
    private final String callerFunction;
    private final String callerModule;
    private final int callerLine;
    private final int callerColumn;

    CallFrame(String callerFunction, String callerModule, int callerLine, int callerColumn) {
        this.callerFunction = callerFunction;
        this.callerModule = callerModule;
        this.callerLine = callerLine;
        this.callerColumn = callerColumn;
    }

    String getCallerFunction() {
        return callerFunction;
    }

    String getCallerModule() {
        return callerModule;
    }

    int getCallerLine() {
        return callerLine;
    }

    int getCallerColumn() {
        return callerColumn;
    }
}
