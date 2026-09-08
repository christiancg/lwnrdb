package org.techhouse.simplejs.internal.interpreter;

record CallFrame(String callerFunction, String callerModule, int callerLine, int callerColumn) {
}
