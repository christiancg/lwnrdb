package org.techhouse.simplejs.builtins.iterator;

import static org.techhouse.simplejs.builtins.IteratorBuiltins.iteratorOf;
import static org.techhouse.simplejs.builtins.IteratorBuiltins.lazyIterator;

import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.IteratorBuiltins.Driver;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class IteratorHelpers {
    public static JsValue map(InterpreterOps ops, Driver source, JsValue fn, JsObject objectProto) {
        final var index = new long[]{0};
        return lazyIterator(() -> {
            final var value = source.next();
            if (value == null) {
                return null;
            }
            return callOrClose(ops, source, fn, List.of(value, new JsNumber(index[0]++)));
        }, source::close, objectProto);
    }

    public static JsValue filter(InterpreterOps ops, Driver source, JsValue fn, JsObject objectProto) {
        final var index = new long[]{0};
        return lazyIterator(() -> {
            JsValue value;
            while ((value = source.next()) != null) {
                if (JsCoercion.toBoolean(callOrClose(ops, source, fn, List.of(value, new JsNumber(index[0]++))))) {
                    return value;
                }
            }
            return null;
        }, source::close, objectProto);
    }

    public static JsValue take(Driver source, long count, JsObject objectProto) {
        final var remaining = new long[]{count};
        return lazyIterator(() -> {
            if (remaining[0] <= 0) {
                source.close();
                return null;
            }
            remaining[0]--;
            return source.next();
        }, source::close, objectProto);
    }

    public static JsValue drop(Driver source, long count, JsObject objectProto) {
        final var remaining = new long[]{count};
        return lazyIterator(() -> {
            while (remaining[0] > 0) {
                remaining[0]--;
                if (source.next() == null) {
                    return null;
                }
            }
            return source.next();
        }, source::close, objectProto);
    }

    public static JsValue flatMap(InterpreterOps ops, Driver source, JsValue fn, JsObject objectProto) {
        final var index = new long[]{0};
        final var inner = new Driver[]{null};
        return lazyIterator(() -> {
            while (true) {
                if (inner[0] != null) {
                    final var innerValue = inner[0].next();
                    if (innerValue != null) {
                        return innerValue;
                    }
                    inner[0] = null;
                }
                final var value = source.next();
                if (value == null) {
                    return null;
                }
                final var mapped = callOrClose(ops, source, fn, List.of(value, new JsNumber(index[0]++)));
                inner[0] = new Driver(ops, flattenOrClose(ops, source, mapped));
            }
        }, () -> {
            if (inner[0] != null) {
                inner[0].close();
            }
            source.close();
        }, objectProto);
    }

    public static JsValue reduce(InterpreterOps ops, Driver source, JsValue fn, List<JsValue> args) {
        var accumulator = args.size() > 1 ? args.get(1) : source.next();
        if (accumulator == null) {
            throw new TypeErrorException("Reduce of empty iterator with no initial value");
        }
        var index = args.size() > 1 ? 0L : 1L;
        JsValue value;
        while ((value = source.next()) != null) {
            accumulator = callOrClose(ops, source, fn, List.of(accumulator, value, new JsNumber(index++)));
        }
        return accumulator;
    }

    public static JsValue toArray(Driver source) {
        final var array = new JsArray();
        JsValue value;
        while ((value = source.next()) != null) {
            array.push(value);
        }
        return array;
    }

    public static JsValue forEach(InterpreterOps ops, Driver source, JsValue fn) {
        var index = 0L;
        JsValue value;
        while ((value = source.next()) != null) {
            callOrClose(ops, source, fn, List.of(value, new JsNumber(index++)));
        }
        return JsUndefined.getInstance();
    }

    public static boolean matchAny(InterpreterOps ops, Driver source, JsValue fn) {
        var index = 0L;
        JsValue value;
        while ((value = source.next()) != null) {
            if (JsCoercion.toBoolean(callOrClose(ops, source, fn, List.of(value, new JsNumber(index++))))) {
                source.close();
                return true;
            }
        }
        return false;
    }

    public static boolean matchAll(InterpreterOps ops, Driver source, JsValue fn) {
        var index = 0L;
        JsValue value;
        while ((value = source.next()) != null) {
            if (!JsCoercion.toBoolean(callOrClose(ops, source, fn, List.of(value, new JsNumber(index++))))) {
                source.close();
                return false;
            }
        }
        return true;
    }

    public static JsValue find(InterpreterOps ops, Driver source, JsValue fn) {
        var index = 0L;
        JsValue value;
        while ((value = source.next()) != null) {
            if (JsCoercion.toBoolean(callOrClose(ops, source, fn, List.of(value, new JsNumber(index++))))) {
                source.close();
                return value;
            }
        }
        return JsUndefined.getInstance();
    }

    public static JsValue callOrClose(InterpreterOps ops, Driver source, JsValue fn, List<JsValue> args) {
        try {
            return ops.call(fn, JsUndefined.getInstance(), args);
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            source.closeAfterThrow();
            throw error;
        }
    }

    public static JsValue flattenOrClose(InterpreterOps ops, Driver source, JsValue mapped) {
        try {
            if (!InterpreterUtils.isObjectLike(mapped)) {
                throw new TypeErrorException("flatMap mapper did not return an object");
            }
            return iteratorOf(ops, mapped);
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            source.closeAfterThrow();
            throw error;
        }
    }

    private IteratorHelpers() {
    }
}
