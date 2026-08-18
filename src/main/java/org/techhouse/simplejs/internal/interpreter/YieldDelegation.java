package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.List;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// The `yield*` loop: it drives the inner iterator entirely through the iterator protocol (the `next`
// read once at GetIterator time, `throw` and `return` looked up with GetMethod semantics) and
// forwards whichever completion the outer generator was resumed with. A throw arrives as a
// JsThrowException out of the suspended yield and a return as a Coroutine.ReturnSignal; the loop
// turns both into the matching call on the inner iterator, and the return completion is re-raised
// carrying the inner iterator's own return value.
public final class YieldDelegation {
    // A synchronous generator hands its consumer the inner iterator's result object untouched, so
    // `done`/`value` are read exactly once. The object travels through the coroutine wrapped in this
    // marker, which the generator's `next`/`return`/`throw` wrappers unwrap instead of building a
    // fresh result object.
    public static final class PassThrough extends JsValue {
        private final JsValue result;

        private PassThrough(JsValue result) {
            this.result = result;
        }

        public JsValue result() {
            return result;
        }
    }

    private enum Mode {
        NEXT, THROW, RETURN
    }

    private final Interpreter interp;
    private final Coroutine coroutine;
    private final boolean async;
    private final boolean fromSync;
    private final JsValue iterator;
    private final JsValue nextMethod;

    private YieldDelegation(Interpreter interp, Coroutine coroutine, JsValue iterable) {
        this.interp = interp;
        this.coroutine = coroutine;
        this.async = coroutine.isAsync();
        final var asyncMethod = async
                ? interp.getMemberByKey(iterable, JsSymbol.ASYNC_ITERATOR)
                : JsUndefined.getInstance();
        this.fromSync = async && isNullish(asyncMethod);
        this.iterator = async && !fromSync
                ? openIterator(iterable, asyncMethod, "Symbol.asyncIterator")
                : openIterator(iterable, interp.getMemberByKey(iterable, JsSymbol.ITERATOR), "Symbol.iterator");
        this.nextMethod = interp.getMember(iterator, "next");
    }

    public static JsValue run(Interpreter interp, Coroutine coroutine, JsValue iterable) {
        return new YieldDelegation(interp, coroutine, iterable).delegate();
    }

    public static JsValue unwrapYielded(Interpreter interp, JsValue yielded) {
        return yielded instanceof PassThrough passThrough ? interp.getMember(passThrough.result(), "value") : yielded;
    }

    private JsValue delegate() {
        var mode = Mode.NEXT;
        var sent = (JsValue) JsUndefined.getInstance();
        while (true) {
            interp.tick();
            final var result = advance(mode, sent);
            if (JsCoercion.toBoolean(interp.getMember(result, "done"))) {
                final var value = resultValue(result, false);
                if (mode == Mode.RETURN) {
                    throw new Coroutine.ReturnSignal(value);
                }
                return value;
            }
            final var yielded = async ? resultValue(result, true) : new PassThrough(result);
            try {
                if (async) {
                    coroutine.markDelegatedYield();
                }
                sent = coroutine.yieldOut(yielded);
                mode = Mode.NEXT;
            } catch (JsThrowException thrown) {
                mode = Mode.THROW;
                sent = thrown.getValue();
            } catch (Coroutine.ReturnSignal signal) {
                mode = Mode.RETURN;
                sent = signal.value();
            }
        }
    }

    private JsValue advance(Mode mode, JsValue sent) {
        return switch (mode) {
            case NEXT -> step(nextMethod, sent);
            case THROW -> throwInto(sent);
            case RETURN -> returnInto(sent);
        };
    }

    private JsValue throwInto(JsValue sent) {
        final var thrower = method("throw");
        if (thrower instanceof JsUndefined) {
            closeIterator();
            throw new TypeErrorException("The iterator does not provide a 'throw' method");
        }
        return step(thrower, sent);
    }

    private JsValue returnInto(JsValue sent) {
        final var returner = method("return");
        if (returner instanceof JsUndefined) {
            throw new Coroutine.ReturnSignal(fromSync ? awaited(sent) : sent);
        }
        return step(returner, sent);
    }

    private JsValue step(JsValue method, JsValue argument) {
        if (!isCallable(method)) {
            throw new TypeErrorException("iterator.next is not a function");
        }
        final var raw = interp.callValue(method, iterator, List.of(argument));
        final var result = async && !fromSync ? awaited(raw) : raw;
        if (!isObjectLike(result)) {
            throw new TypeErrorException("Iterator result is not an object");
        }
        return result;
    }

    // AsyncFromSyncIteratorContinuation awaits only the step's `value`, and on a rejection of a
    // not-done step it closes the wrapped synchronous iterator before the rejection propagates.
    private JsValue resultValue(JsValue result, boolean closeOnRejection) {
        final var value = interp.getMember(result, "value");
        if (!fromSync) {
            return value;
        }
        try {
            return awaited(value);
        } catch (JsThrowException | TypeErrorException rejection) {
            if (closeOnRejection) {
                closeQuietly();
            }
            throw rejection;
        }
    }

    private void closeQuietly() {
        try {
            final var returner = interp.getMember(iterator, "return");
            if (isCallable(returner)) {
                interp.callValue(returner, iterator, List.of());
            }
        } catch (JsThrowException | TypeErrorException ignored) {
            // the original rejection wins over anything the iterator's `return` throws
        }
    }

    private JsValue method(String name) {
        final var candidate = interp.getMember(iterator, name);
        if (isNullish(candidate)) {
            return JsUndefined.getInstance();
        }
        if (!isCallable(candidate)) {
            throw new TypeErrorException("iterator." + name + " is not a function");
        }
        return candidate;
    }

    private void closeIterator() {
        final var returner = method("return");
        if (returner instanceof JsUndefined) {
            return;
        }
        final var raw = interp.callValue(returner, iterator, List.of());
        final var result = async ? awaited(raw) : raw;
        if (!isObjectLike(result)) {
            throw new TypeErrorException("Iterator result is not an object");
        }
    }

    private JsValue awaited(JsValue value) {
        return coroutine.await(interp.toPromise(value));
    }

    private JsValue openIterator(JsValue iterable, JsValue method, String name) {
        if (isNullish(method)) {
            throw new TypeErrorException(JsCoercion.toStr(iterable) + " is not iterable");
        }
        if (!isCallable(method)) {
            throw new TypeErrorException(name + " is not a function");
        }
        final var opened = interp.callValue(method, iterable, List.of());
        if (!isObjectLike(opened)) {
            throw new TypeErrorException("Result of " + name + " method is not an object");
        }
        return opened;
    }
}
