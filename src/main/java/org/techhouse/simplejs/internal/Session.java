package org.techhouse.simplejs.internal;

import java.util.List;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.internal.interpreter.StackCapture;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class Session implements AutoCloseable {
    public final Interpreter interpreter;
    public final Interpreter.ProgramOutcome outcome;
    public boolean closed;

    Session(Interpreter interpreter, Interpreter.ProgramOutcome outcome) {
        this.interpreter = interpreter;
        this.outcome = outcome;
    }

    public Interpreter.ProgramOutcome outcome() {
        return outcome;
    }

    public JsValue call(JsValue fn, List<JsValue> args) {
        if (closed) {
            throw new SimpleJsRuntimeException("Script session is closed");
        }
        StackCapture.install(interpreter.callStack);
        try {
            final var value = interpreter.callValue(fn, JsUndefined.getInstance(), args);
            interpreter.eventLoop.drain(interpreter.deadlineNanos);
            return value;
        } finally {
            StackCapture.uninstall(interpreter.callStack);
        }
    }

    public void charge(long bytes) {
        interpreter.charge(bytes);
    }

    public void release(long bytes) {
        interpreter.release(bytes);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        StackCapture.install(interpreter.callStack);
        try {
            interpreter.eventLoop.drain(interpreter.deadlineNanos);
            interpreter.reportUnhandledRejections();
        } catch (RuntimeException | Error ignored) {
        } finally {
            interpreter.cancelPendingCoroutines();
            StackCapture.uninstall(interpreter.callStack);
        }
    }
}
