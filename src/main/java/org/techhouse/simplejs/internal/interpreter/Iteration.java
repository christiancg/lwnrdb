package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.arrayLikeElements;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsObject;
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
    private final JsObject iterator;
    private int index;

    public Iteration(Interpreter interp, JsValue iterable) {
        this.interp = interp;
        if (iterable instanceof JsGenerator gen) {
            this.generator = gen;
            this.buffer = null;
            this.iterator = null;
        } else if (iterable instanceof JsArray || iterable instanceof JsString || iterable instanceof JsArguments
                || iterable instanceof JsTypedArray) {
            this.generator = null;
            this.buffer = arrayLikeElements(iterable);
            this.iterator = null;
        } else {
            this.generator = null;
            this.buffer = null;
            this.iterator = openIterator(iterable);
        }
    }

    public JsValue next() {
        if (generator != null) {
            final var step = generator.getCoroutine().resumeNext(JsUndefined.getInstance());
            return step.done() ? null : step.value();
        }
        if (iterator != null) {
            final var nextFn = interp.getMember(iterator, "next");
            if (!isCallable(nextFn)) {
                throw new TypeErrorException("iterator.next is not a function");
            }
            final var step = interp.callValue(nextFn, iterator, List.of());
            return JsCoercion.toBoolean(interp.getMember(step, "done")) ? null : interp.getMember(step, "value");
        }
        return index < buffer.size() ? buffer.get(index++) : null;
    }

    public void close() {
        if (generator != null && !generator.getCoroutine().isDone()) {
            generator.getCoroutine().resumeReturn(JsUndefined.getInstance());
        }
        if (iterator != null) {
            final var returnFn = interp.getMember(iterator, "return");
            if (isCallable(returnFn)) {
                interp.callValue(returnFn, iterator, List.of());
            }
        }
    }

    private JsObject openIterator(JsValue iterable) {
        final var iterFn = interp.getMemberByKey(iterable, JsSymbol.ITERATOR);
        if (!isCallable(iterFn)) {
            throw new TypeErrorException(JsCoercion.toStr(iterable) + " is not iterable");
        }
        final var iter = interp.callValue(iterFn, iterable, List.of());
        if (!(iter instanceof JsObject object)) {
            throw new TypeErrorException("Result of Symbol.iterator method is not an object");
        }
        return object;
    }
}
