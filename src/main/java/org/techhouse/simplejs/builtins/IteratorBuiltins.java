package org.techhouse.simplejs.builtins;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * ES2025 iterator helpers. The {@code Iterator} global exposes {@code Iterator.from} plus a
 * {@code prototype} carrying the helpers; the interpreter also routes helper names on generators
 * and on any iterator-like object (one with an own callable {@code next}) to {@link #helper}, so a
 * lazy helper's own result chains too. Helpers drive their receiver iterator directly through the
 * {@link InterpreterOps} seam (GetIteratorDirect — no {@code Symbol.iterator} re-invocation).
 */
public final class IteratorBuiltins {
    private static final Set<String> HELPERS = Set.of("map", "filter", "take", "drop", "flatMap", "reduce", "toArray",
            "forEach", "some", "every", "find");

    private IteratorBuiltins() {
    }

    public static boolean isHelperName(String name) {
        return HELPERS.contains(name);
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        // Direct construction passes JsUndefined as thisArg; a super() call from a subclass passes
        // the instance under construction instead (see ClassEvaluator.applyNativeSuper), which is the
        // only signal that distinguishes the spec-legal super() case from a direct `new Iterator()`.
        final var ctor = new JsNativeFunction("Iterator", (thisArg, _) -> {
            if (thisArg instanceof JsUndefined) {
                throw new TypeErrorException("Abstract class Iterator not directly constructable");
            }
            return thisArg;
        });
        final var prototype = new JsObject();
        for (final var name : HELPERS) {
            prototype.set(name, helper(ops, name));
        }
        prototype.setSymbol(JsSymbol.ITERATOR, new JsNativeFunction("[Symbol.iterator]", (thisArg, _) -> thisArg));
        ctor.setProperty("prototype", prototype);
        ctor.setProperty("from", new JsNativeFunction("from", (_, args) -> from(ops, arg0(args))));
        return ctor;
    }

    public static JsNativeFunction helper(InterpreterOps ops, String name) {
        return new JsNativeFunction(name, (thisArg, args) -> dispatch(ops, name, thisArg, args));
    }

    private static JsValue dispatch(InterpreterOps ops, String name, JsValue thisArg, List<JsValue> args) {
        final var source = new Driver(ops, requireIterator(thisArg));
        return switch (name) {
            case "map" -> map(ops, source, callback(args));
            case "filter" -> filter(ops, source, callback(args));
            case "take" -> take(source, limit(arg0(args)));
            case "drop" -> drop(source, limit(arg0(args)));
            case "flatMap" -> flatMap(ops, source, callback(args));
            case "reduce" -> reduce(ops, source, callback(args), args);
            case "toArray" -> toArray(source);
            case "forEach" -> forEach(ops, source, callback(args));
            case "some" -> JsBoolean.of(matchAny(ops, source, callback(args)));
            case "every" -> JsBoolean.of(matchAll(ops, source, callback(args)));
            case "find" -> find(ops, source, callback(args));
            default -> JsUndefined.getInstance();
        };
    }

    private static JsValue from(InterpreterOps ops, JsValue value) {
        final var driver = new Driver(ops, iteratorOf(ops, value));
        return lazyIterator(driver::next);
    }

    private static JsValue map(InterpreterOps ops, Driver source, JsValue fn) {
        final var index = new long[]{0};
        return lazyIterator(() -> {
            final var value = source.next();
            if (value == null) {
                return null;
            }
            return ops.call(fn, JsUndefined.getInstance(), List.of(value, new JsNumber(index[0]++)));
        });
    }

    private static JsValue filter(InterpreterOps ops, Driver source, JsValue fn) {
        final var index = new long[]{0};
        return lazyIterator(() -> {
            JsValue value;
            while ((value = source.next()) != null) {
                if (JsCoercion
                        .toBoolean(ops.call(fn, JsUndefined.getInstance(), List.of(value, new JsNumber(index[0]++))))) {
                    return value;
                }
            }
            return null;
        });
    }

    private static JsValue take(Driver source, long count) {
        final var remaining = new long[]{count};
        return lazyIterator(() -> {
            if (remaining[0] <= 0) {
                source.close();
                return null;
            }
            remaining[0]--;
            return source.next();
        });
    }

    private static JsValue drop(Driver source, long count) {
        final var remaining = new long[]{count};
        return lazyIterator(() -> {
            while (remaining[0] > 0) {
                remaining[0]--;
                if (source.next() == null) {
                    return null;
                }
            }
            return source.next();
        });
    }

    private static JsValue flatMap(InterpreterOps ops, Driver source, JsValue fn) {
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
                final var mapped = ops.call(fn, JsUndefined.getInstance(), List.of(value, new JsNumber(index[0]++)));
                inner[0] = new Driver(ops, iteratorOf(ops, mapped));
            }
        });
    }

    private static JsValue reduce(InterpreterOps ops, Driver source, JsValue fn, List<JsValue> args) {
        var accumulator = args.size() > 1 ? args.get(1) : source.next();
        if (accumulator == null) {
            throw new TypeErrorException("Reduce of empty iterator with no initial value");
        }
        var index = args.size() > 1 ? 0L : 1L;
        JsValue value;
        while ((value = source.next()) != null) {
            accumulator = ops.call(fn, JsUndefined.getInstance(), List.of(accumulator, value, new JsNumber(index++)));
        }
        return accumulator;
    }

    private static JsValue toArray(Driver source) {
        final var array = new JsArray();
        JsValue value;
        while ((value = source.next()) != null) {
            array.push(value);
        }
        return array;
    }

    private static JsValue forEach(InterpreterOps ops, Driver source, JsValue fn) {
        var index = 0L;
        JsValue value;
        while ((value = source.next()) != null) {
            ops.call(fn, JsUndefined.getInstance(), List.of(value, new JsNumber(index++)));
        }
        return JsUndefined.getInstance();
    }

    private static boolean matchAny(InterpreterOps ops, Driver source, JsValue fn) {
        var index = 0L;
        JsValue value;
        while ((value = source.next()) != null) {
            if (JsCoercion.toBoolean(ops.call(fn, JsUndefined.getInstance(), List.of(value, new JsNumber(index++))))) {
                source.close();
                return true;
            }
        }
        return false;
    }

    private static boolean matchAll(InterpreterOps ops, Driver source, JsValue fn) {
        var index = 0L;
        JsValue value;
        while ((value = source.next()) != null) {
            if (!JsCoercion.toBoolean(ops.call(fn, JsUndefined.getInstance(), List.of(value, new JsNumber(index++))))) {
                source.close();
                return false;
            }
        }
        return true;
    }

    private static JsValue find(InterpreterOps ops, Driver source, JsValue fn) {
        var index = 0L;
        JsValue value;
        while ((value = source.next()) != null) {
            if (JsCoercion.toBoolean(ops.call(fn, JsUndefined.getInstance(), List.of(value, new JsNumber(index++))))) {
                source.close();
                return value;
            }
        }
        return JsUndefined.getInstance();
    }

    private static JsObject lazyIterator(Supplier<JsValue> nextValue) {
        final var iterator = new JsObject();
        iterator.set("next", new JsNativeFunction("next", (_, _) -> {
            final var value = nextValue.get();
            final var step = new JsObject();
            step.set("value", value == null ? JsUndefined.getInstance() : value);
            step.set("done", JsBoolean.of(value == null));
            return step;
        }));
        iterator.setSymbol(JsSymbol.ITERATOR, new JsNativeFunction("[Symbol.iterator]", (_, _) -> iterator));
        return iterator;
    }

    private static JsValue iteratorOf(InterpreterOps ops, JsValue value) {
        final var iterFn = ops.getMember(value, JsSymbol.ITERATOR);
        if (iterFn instanceof JsFunction || iterFn instanceof JsNativeFunction) {
            return ops.call(iterFn, value, List.of());
        }
        return requireIterator(value);
    }

    private static JsValue requireIterator(JsValue value) {
        if (value instanceof JsUndefined || value == null) {
            throw new TypeErrorException("Iterator helper called on non-iterator");
        }
        return value;
    }

    private static JsValue callback(List<JsValue> args) {
        final var fn = arg0(args);
        if (!(fn instanceof JsFunction) && !(fn instanceof JsNativeFunction)) {
            throw new TypeErrorException("Iterator helper callback is not a function");
        }
        return fn;
    }

    private static long limit(JsValue value) {
        final var number = JsCoercion.toNumber(value);
        if (Double.isNaN(number) || number < 0) {
            throw new RangeErrorException("Iterator helper limit must be a non-negative number");
        }
        return (long) number;
    }

    private static JsValue arg0(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }

    private static final class Driver {
        private final InterpreterOps ops;
        private final JsValue iterator;
        private boolean done;

        private Driver(InterpreterOps ops, JsValue iterator) {
            this.ops = ops;
            this.iterator = iterator;
        }

        private JsValue next() {
            if (done) {
                return null;
            }
            final var nextFn = ops.getMember(iterator, new JsString("next"));
            if (!(nextFn instanceof JsFunction) && !(nextFn instanceof JsNativeFunction)) {
                throw new TypeErrorException("iterator.next is not a function");
            }
            final var step = ops.call(nextFn, iterator, List.of());
            if (JsCoercion.toBoolean(ops.getMember(step, new JsString("done")))) {
                done = true;
                return null;
            }
            return ops.getMember(step, new JsString("value"));
        }

        private void close() {
            if (done) {
                return;
            }
            done = true;
            final var returnFn = ops.getMember(iterator, new JsString("return"));
            if (returnFn instanceof JsFunction || returnFn instanceof JsNativeFunction) {
                ops.call(returnFn, iterator, List.of());
            }
        }
    }
}
