package org.techhouse.simplejs.internal;

import java.util.List;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.values.EJsonInterop;
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
        try {
            final var value = interpreter.callValue(fn, JsUndefined.getInstance(), args);
            interpreter.eventLoop.drain(interpreter.deadlineNanos);
            return value;
        } catch (SimpleJsRuntimeException error) {
            throw interpreter.callStack.stamp(error);
        }
    }

    public JsonBaseElement toHostEjson(JsValue value) {
        if (closed) {
            throw new SimpleJsRuntimeException("Script session is closed");
        }
        try {
            return EJsonInterop.toHostEjson(value, interpreter.ops());
        } catch (SimpleJsRuntimeException error) {
            throw interpreter.callStack.stamp(error);
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
        try {
            interpreter.eventLoop.drain(interpreter.deadlineNanos);
            interpreter.reportUnhandledRejections();
        } catch (RuntimeException | Error ignored) {
        } finally {
            interpreter.cancelPendingCoroutines();
        }
    }
}
