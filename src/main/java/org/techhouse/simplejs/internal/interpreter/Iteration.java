package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.iterableElements;

import java.util.List;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// One pass over an iterable, hiding the three concrete iteration mechanisms behind next()/close():
// a generator's coroutine, a materialised buffer for the array-like values, or an external iterator
// object driven through the Symbol.iterator protocol. Re-entry into the interpreter (opening the
// iterator, calling next/return) routes through the Interpreter seam.
public final class Iteration {
    private final Interpreter interp;
    private final JsGenerator generator;
    private final List<JsValue> buffer;
    private final JsValue indexed;
    private final JsValue iterator;
    // GetIterator reads `next` once and every step calls that same function, so a script replacing
    // the property mid-iteration is not observed.
    private final JsValue nextMethod;
    private int index;

    public Iteration(Interpreter interp, JsValue iterable) {
        this.interp = interp;
        if (iterable instanceof JsGenerator gen) {
            this.generator = gen;
            this.buffer = null;
            this.indexed = null;
            this.iterator = null;
        } else if (usesDefaultIterator(interp, iterable)) {
            this.generator = null;
            // %ArrayIteratorPrototype%.next re-reads length and Get(index) on every step, so an array
            // or typed array is walked lazily; a string's code points cannot change under it.
            this.buffer = iterable instanceof JsString string ? iterableElements(string) : null;
            this.indexed = buffer == null ? iterable : null;
            this.iterator = null;
        } else {
            this.generator = null;
            this.buffer = null;
            this.indexed = null;
            this.iterator = openIterator(iterable);
        }
        this.nextMethod = iterator == null ? null : interp.getMember(iterator, "next");
    }

    public JsValue next() {
        if (generator != null) {
            final var step = generator.getCoroutine().resumeNext(JsUndefined.getInstance());
            return step.done() ? null : YieldDelegation.unwrapYielded(interp, step.value());
        }
        if (iterator != null) {
            if (!isCallable(nextMethod)) {
                throw new TypeErrorException("iterator.next is not a function");
            }
            final var step = interp.callValue(nextMethod, iterator, List.of());
            if (!isObjectLike(step)) {
                throw new TypeErrorException("Iterator result is not an object");
            }
            return JsCoercion.toBoolean(interp.getMember(step, "done")) ? null : interp.getMember(step, "value");
        }
        if (indexed != null) {
            return index < currentLength(indexed) ? interp.getMember(indexed, Integer.toString(index++)) : null;
        }
        return index < buffer.size() ? buffer.get(index++) : null;
    }

    private static int currentLength(JsValue target) {
        return switch (target) {
            case JsArray array -> array.getElements().size();
            case JsArguments arguments -> arguments.snapshot().size();
            case JsTypedArray typed -> typed.length();
            default -> 0;
        };
    }

    // IteratorClose under a normal completion: a `return` that is present but not callable, or that
    // answers a non-object, is a TypeError the caller sees.
    public void close() {
        if (generator != null) {
            if (!generator.getCoroutine().isDone()) {
                generator.getCoroutine().resumeReturn(JsUndefined.getInstance());
            }
            return;
        }
        if (iterator == null) {
            return;
        }
        final var returnFn = interp.getMember(iterator, "return");
        if (isNullish(returnFn)) {
            return;
        }
        if (!isCallable(returnFn)) {
            throw new TypeErrorException("iterator.return is not a function");
        }
        final var result = interp.callValue(returnFn, iterator, List.of());
        if (!isObjectLike(result)) {
            throw new TypeErrorException("Iterator result is not an object");
        }
    }

    // IteratorClose under a throw completion: the pending error is the one that propagates, so
    // everything the close itself raises is discarded.
    public void closeAfterThrow() {
        try {
            close();
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException ignored) {
            // discarded on purpose: the original throw completion wins
        }
    }

    // Reading straight out of the backing storage is only equivalent to running the protocol while
    // @@iterator is still the intrinsic one, so a script that replaces or deletes it drops onto the
    // general external-iterator path.
    public static boolean usesDefaultIterator(Interpreter interp, JsValue iterable) {
        if (!(iterable instanceof JsArray || iterable instanceof JsString || iterable instanceof JsArguments
                || iterable instanceof JsTypedArray)) {
            return false;
        }
        return interp.intrinsics().isDefaultIterator(iterable, interp.getMemberByKey(iterable, JsSymbol.ITERATOR));
    }

    private JsValue openIterator(JsValue iterable) {
        final var iterFn = interp.getMemberByKey(iterable, JsSymbol.ITERATOR);
        if (!isCallable(iterFn)) {
            throw new TypeErrorException(JsCoercion.toStr(iterable) + " is not iterable");
        }
        final var iter = interp.callValue(iterFn, iterable, List.of());
        if (!isObjectLike(iter)) {
            throw new TypeErrorException("Result of Symbol.iterator method is not an object");
        }
        return iter;
    }
}
