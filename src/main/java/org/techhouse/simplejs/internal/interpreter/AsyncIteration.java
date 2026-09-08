package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.List;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class AsyncIteration {
    private final Interpreter interp;
    private final EventLoop eventLoop;
    private final JsValue iterator;
    private final JsValue nextMethod;
    private final boolean fromSync;
    private boolean done;

    private AsyncIteration(Interpreter interp, JsValue iterator, JsValue nextMethod, boolean fromSync) {
        this.interp = interp;
        this.eventLoop = interp.eventLoop();
        this.iterator = iterator;
        this.nextMethod = nextMethod;
        this.fromSync = fromSync;
    }

    public static AsyncIteration open(Interpreter interp, JsValue source) {
        final var asyncMethod = interp.getMemberByKey(source, JsSymbol.ASYNC_ITERATOR);
        if (!isNullish(asyncMethod)) {
            final var opened = openWith(interp, source, asyncMethod, "Symbol.asyncIterator");
            return new AsyncIteration(interp, opened, interp.getMember(opened, "next"), false);
        }
        final var syncMethod = interp.getMemberByKey(source, JsSymbol.ITERATOR);
        if (isNullish(syncMethod)) {
            throw new TypeErrorException(JsCoercion.toStr(source) + " is not async iterable");
        }
        final var opened = openWith(interp, source, syncMethod, "Symbol.iterator");
        return new AsyncIteration(interp, opened, interp.getMember(opened, "next"), true);
    }

    private static JsValue openWith(Interpreter interp, JsValue source, JsValue method, String label) {
        if (!isCallable(method)) {
            throw new TypeErrorException(label + " is not a function");
        }
        final var opened = interp.callValue(method, source, List.of());
        if (!isObjectLike(opened)) {
            throw new TypeErrorException("Result of " + label + " method is not an object");
        }
        return opened;
    }

    public record Step(boolean done, JsValue value) {
    }

    public Step step(Coroutine coroutine, JsValue sent) {
        return step(coroutine, sent instanceof JsUndefined ? List.of() : List.of(sent));
    }

    private Step step(Coroutine coroutine, List<JsValue> args) {
        if (done) {
            return new Step(true, JsUndefined.getInstance());
        }
        if (!isCallable(nextMethod)) {
            throw new TypeErrorException("iterator.next is not a function");
        }
        final var raw = interp.callValue(nextMethod, iterator, args);
        final var step = fromSync ? continuation(coroutine, raw) : awaitStep(coroutine, raw);
        if (!step.done()) {
            coroutine.markDelegatedYield();
        }
        return step;
    }

    private Step awaitStep(Coroutine coroutine, JsValue raw) {
        final var settled = coroutine.await(interp.toPromise(raw));
        if (!isObjectLike(settled)) {
            throw new TypeErrorException("Iterator result is not an object");
        }
        final var complete = JsCoercion.toBoolean(interp.getMember(settled, "done"));
        done = done || complete;
        return new Step(complete, interp.getMember(settled, "value"));
    }

    private Step continuation(Coroutine coroutine, JsValue raw) {
        if (!isObjectLike(raw)) {
            done = true;
            throw new TypeErrorException("Iterator result is not an object");
        }
        final boolean complete;
        final JsValue value;
        try {
            complete = JsCoercion.toBoolean(interp.getMember(raw, "done"));
            value = interp.getMember(raw, "value");
        } catch (SimpleJsRuntimeException error) {
            done = true;
            throw error;
        }
        done = done || complete;

        final var nextResult = new JsPromise(eventLoop);
        try {
            final var valueWrapper = interp.toPromise(value);
            valueWrapper.subscribe(
                    awaitedValue -> nextResult.resolve(InterpreterUtils.stepResult(awaitedValue, complete)),
                    nextResult::reject);
        } catch (SimpleJsRuntimeException error) {
            nextResult.reject(InterpreterUtils.toErrorValue(error, interp.intrinsics()));
        }

        try {
            final var settled = coroutine.await(interp.toPromise(nextResult));
            if (!isObjectLike(settled)) {
                throw new TypeErrorException("Iterator result is not an object");
            }
            return new Step(complete, interp.getMember(settled, "value"));
        } catch (SimpleJsRuntimeException error) {
            if (!complete) {
                closeSyncQuietly();
                done = true;
            }
            throw error;
        }
    }

    public void close() {
        if (done) {
            return;
        }
        done = true;
        final var returnFn = interp.getMember(iterator, "return");
        if (isNullish(returnFn)) {
            return;
        }
        if (!isCallable(returnFn)) {
            throw new TypeErrorException("iterator.return is not a function");
        }
        interp.callValue(returnFn, iterator, List.of());
    }

    private void closeSyncQuietly() {
        try {
            final var returnFn = interp.getMember(iterator, "return");
            if (isCallable(returnFn)) {
                interp.callValue(returnFn, iterator, List.of());
            }
        } catch (SimpleJsRuntimeException ignored) {
        }
    }
}
