package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// GetIterator(obj, async) plus the step loop it feeds, shared by `for await` and async `yield*`.
// The two differ only in where the await happens: a real async iterator returns a promise *of* the
// step object, while a sync iterator opened through CreateAsyncFromSyncIterator returns the step
// object directly and only its `value` is awaited.
public final class AsyncIteration {
    private final Interpreter interp;
    private final JsValue iterator;
    private final boolean fromSync;
    // Generators and the array-likes have no reachable `next` property to drive, so they fall back
    // to the shared synchronous Iteration and only their values are awaited.
    private final Iteration syncIteration;

    private AsyncIteration(Interpreter interp, JsValue iterator, boolean fromSync, Iteration syncIteration) {
        this.interp = interp;
        this.iterator = iterator;
        this.fromSync = fromSync;
        this.syncIteration = syncIteration;
    }

    // GetMethod is not the same as a plain member read: a present-but-non-callable @@asyncIterator
    // is a TypeError, and @@iterator must not be touched at all when @@asyncIterator is present.
    public static AsyncIteration open(Interpreter interp, JsValue source) {
        final var asyncMethod = interp.getMemberByKey(source, JsSymbol.ASYNC_ITERATOR);
        if (!isNullish(asyncMethod)) {
            final var opened = openWith(interp, source, asyncMethod);
            return new AsyncIteration(interp, opened, false, null);
        }
        final var syncMethod = interp.getMemberByKey(source, JsSymbol.ITERATOR);
        if (isNullish(syncMethod)) {
            throw new TypeErrorException(JsCoercion.toStr(source) + " is not async iterable");
        }
        if (!isCallable(syncMethod)) {
            throw new TypeErrorException("Symbol.iterator is not a function");
        }
        return new AsyncIteration(interp, null, true, new Iteration(interp, source));
    }

    private static JsValue openWith(Interpreter interp, JsValue source, JsValue method) {
        if (!isCallable(method)) {
            throw new TypeErrorException("Symbol.asyncIterator" + " is not a function");
        }
        final var opened = interp.callValue(method, source, List.of());
        if (!isObjectLike(opened)) {
            throw new TypeErrorException("Result of " + "Symbol.asyncIterator" + " method is not an object");
        }
        return opened;
    }

    public JsValue getIterator() {
        return iterator;
    }

    public record Step(boolean done, JsValue value) {
    }

    public Step step(Coroutine coroutine, JsValue sent) {
        if (fromSync) {
            final var value = syncIteration.next();
            return value == null
                    ? new Step(true, JsUndefined.getInstance())
                    : new Step(false, coroutine.await(interp.toPromise(value)));
        }
        final var nextFn = interp.getMember(iterator, "next");
        if (!isCallable(nextFn)) {
            throw new TypeErrorException("iterator.next is not a function");
        }
        // The spec passes no argument at all when there is no sent value, which a `next` counting
        // its arguments can observe.
        final var raw = interp.callValue(nextFn, iterator, sent instanceof JsUndefined ? List.of() : List.of(sent));
        final var settled = coroutine.await(interp.toPromise(raw));
        if (!isObjectLike(settled)) {
            throw new TypeErrorException("Iterator result is not an object");
        }
        return new Step(JsCoercion.toBoolean(interp.getMember(settled, "done")), interp.getMember(settled, "value"));
    }

    public void close() {
        if (fromSync) {
            syncIteration.close();
            return;
        }
        final var returnFn = interp.getMember(iterator, "return");
        if (isCallable(returnFn)) {
            interp.callValue(returnFn, iterator, List.of(JsUndefined.getInstance()));
        }
    }
}
