package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.iterableElements;

import java.util.List;
import java.util.function.Consumer;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class Iteration {
    private final Interpreter interp;
    private final InterpreterOps ops;
    private final JsGenerator generator;
    private final List<JsValue> buffer;
    private final JsValue indexed;
    private final JsValue iterator;
    private final JsValue nextMethod;
    private int index;

    public Iteration(InterpreterOps ops, JsValue iterable) {
        this.interp = null;
        this.ops = ops;
        this.generator = null;
        this.buffer = null;
        this.indexed = null;
        this.iterator = openIterator(iterable);
        this.nextMethod = read(iterator, "next");
    }

    public Iteration(Interpreter interp, JsValue iterable) {
        this.interp = interp;
        this.ops = null;
        if (iterable instanceof JsGenerator gen) {
            this.generator = gen;
            this.buffer = null;
            this.indexed = null;
            this.iterator = null;
        } else if (usesDefaultIterator(interp, iterable)) {
            this.generator = null;
            this.buffer = iterable instanceof JsString string ? iterableElements(string) : null;
            this.indexed = buffer == null ? iterable : null;
            this.iterator = null;
        } else {
            this.generator = null;
            this.buffer = null;
            this.indexed = null;
            this.iterator = openIterator(iterable);
        }
        this.nextMethod = iterator == null ? null : read(iterator, "next");
    }

    private JsValue read(JsValue target, String key) {
        return interp == null ? ops.getMember(target, new JsString(key)) : interp.getMember(target, key);
    }

    private JsValue invoke(JsValue fn, JsValue thisArg) {
        return interp == null ? ops.call(fn, thisArg, List.of()) : interp.callValue(fn, thisArg, List.of());
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
            final var step = invoke(nextMethod, iterator);
            if (!isObjectLike(step)) {
                throw new TypeErrorException("Iterator result is not an object");
            }
            return JsCoercion.toBoolean(read(step, "done")) ? null : read(step, "value");
        }
        if (indexed != null) {
            if (indexed instanceof JsTypedArray typed && typed.isOutOfBounds()) {
                throw new TypeErrorException("Cannot iterate a typed array over a detached buffer");
            }
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
        final var returnFn = read(iterator, "return");
        if (isNullish(returnFn)) {
            return;
        }
        if (!isCallable(returnFn)) {
            throw new TypeErrorException("iterator.return is not a function");
        }
        final var result = invoke(returnFn, iterator);
        if (!isObjectLike(result)) {
            throw new TypeErrorException("Iterator result is not an object");
        }
    }

    public void forEach(Consumer<JsValue> body) {
        for (var element = next(); element != null; element = next()) {
            try {
                body.accept(element);
            } catch (ScriptAbortException abort) {
                throw abort;
            } catch (RuntimeException error) {
                closeAfterThrow();
                throw error;
            }
        }
    }

    public void closeAfterThrow() {
        try {
            close();
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException ignored) {
        }
    }

    public static boolean usesDefaultIterator(Interpreter interp, JsValue iterable) {
        if (!(iterable instanceof JsArray || iterable instanceof JsString || iterable instanceof JsArguments
                || iterable instanceof JsTypedArray)) {
            return false;
        }
        final var candidate = interp.getMemberByKey(iterable, JsSymbol.ITERATOR);
        if (!interp.intrinsics().isDefaultIterator(iterable, candidate)) {
            return false;
        }
        final var probe = interp.callValue(candidate, iterable, List.of());
        return interp.getMember(probe, "next") instanceof JsNativeFunction;
    }

    private JsValue openIterator(JsValue iterable) {
        final var iterFn = interp == null
                ? ops.getMember(iterable, JsSymbol.ITERATOR)
                : interp.getMemberByKey(iterable, JsSymbol.ITERATOR);
        if (!isCallable(iterFn)) {
            throw new TypeErrorException(JsCoercion.toStr(iterable) + " is not iterable");
        }
        final var iter = invoke(iterFn, iterable);
        if (!isObjectLike(iter)) {
            throw new TypeErrorException("Result of Symbol.iterator method is not an object");
        }
        return iter;
    }
}
