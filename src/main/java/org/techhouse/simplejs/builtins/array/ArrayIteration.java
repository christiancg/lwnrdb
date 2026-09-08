package org.techhouse.simplejs.builtins.array;

import static org.techhouse.simplejs.builtins.ArrayBuiltins.TO_STRING;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.relativeIndex;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.thisArg;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.toIntegerOrInfinity;
import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.BuiltinArgs.callback;
import static org.techhouse.simplejs.builtins.array.ArrayConstruct.createDataPropertyOrThrow;
import static org.techhouse.simplejs.builtins.array.ArrayConstruct.speciesCreate;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Invoker;
import org.techhouse.simplejs.builtins.JsIterators;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.SameValueZero;

public final class ArrayIteration {
    public static String toLocaleString(ArrayLike target, Invoker invoker, InterpreterOps ops) {
        final var length = target.length();
        final var sb = new StringBuilder();
        for (var i = 0L; i < length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            final var element = target.get(i);
            if (element instanceof JsNull || element instanceof JsUndefined) {
                continue;
            }
            final var method = ops == null
                    ? JsUndefined.getInstance()
                    : ops.getMember(element, new JsString("toLocaleString"));
            if (InterpreterUtils.isCallable(method)) {
                sb.append(JsCoercion.toStr(invoker.call(method, element, List.of()), ops));
                continue;
            }
            final var toString = ops == null ? JsUndefined.getInstance() : ops.getMember(element, TO_STRING);
            if (InterpreterUtils.isCallable(toString)) {
                sb.append(JsCoercion.toStr(invoker.call(toString, element, List.of()), ops));
            } else {
                sb.append(JsCoercion.toStr(element, ops));
            }
        }
        return sb.toString();
    }

    public static JsValue map(ArrayLike target, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        final var result = speciesCreate(target, length, ops);
        for (var i = 0L; i < length; i++) {
            if (target.has(i)) {
                final var mapped = invoker.call(callback, self,
                        List.of(target.get(i), new JsNumber(i), target.value()));
                createDataPropertyOrThrow(result, i, mapped, ops);
            }
        }
        return result;
    }

    public static JsValue filter(ArrayLike target, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        final var result = speciesCreate(target, 0, ops);
        var kept = 0L;
        for (var i = 0L; i < length; i++) {
            if (!target.has(i)) {
                continue;
            }
            final var element = target.get(i);
            if (JsCoercion.toBoolean(invoker.call(callback, self, List.of(element, new JsNumber(i), target.value())))) {
                createDataPropertyOrThrow(result, kept, element, ops);
                kept++;
            }
        }
        return result;
    }

    public static JsValue reduce(ArrayLike target, List<JsValue> args, Invoker invoker) {
        final var length = target.length();
        final var callback = callback(args);
        var index = 0L;
        JsValue accumulator = null;
        if (args.size() >= 2) {
            accumulator = args.get(1);
        } else {
            while (accumulator == null && index < length) {
                if (target.has(index)) {
                    accumulator = target.get(index);
                }
                index++;
            }
            if (accumulator == null) {
                throw new TypeErrorException("Reduce of empty array with no initial value");
            }
        }
        for (var i = index; i < length; i++) {
            if (target.has(i)) {
                accumulator = invoker.call(callback, JsUndefined.getInstance(),
                        List.of(accumulator, target.get(i), new JsNumber(i), target.value()));
            }
        }
        return accumulator;
    }

    public static JsValue reduceRight(ArrayLike target, List<JsValue> args, Invoker invoker) {
        var index = target.length() - 1;
        final var callback = callback(args);
        JsValue accumulator = null;
        if (args.size() >= 2) {
            accumulator = args.get(1);
        } else {
            while (accumulator == null && index >= 0) {
                if (target.has(index)) {
                    accumulator = target.get(index);
                }
                index--;
            }
            if (accumulator == null) {
                throw new TypeErrorException("Reduce of empty array with no initial value");
            }
        }
        for (var i = index; i >= 0; i--) {
            if (target.has(i)) {
                accumulator = invoker.call(callback, JsUndefined.getInstance(),
                        List.of(accumulator, target.get(i), new JsNumber(i), target.value()));
            }
        }
        return accumulator;
    }

    public static JsValue forEach(ArrayLike target, List<JsValue> args, Invoker invoker) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        for (var i = 0L; i < length; i++) {
            if (target.has(i)) {
                invoker.call(callback, self, List.of(target.get(i), new JsNumber(i), target.value()));
            }
        }
        return JsUndefined.getInstance();
    }

    public static JsValue find(ArrayLike target, List<JsValue> args, Invoker invoker, boolean forwards) {
        final var found = findMatch(target, args, invoker, forwards);
        return found == null ? JsUndefined.getInstance() : found.value;
    }

    public static long findIndex(ArrayLike target, List<JsValue> args, Invoker invoker, boolean forwards) {
        final var found = findMatch(target, args, invoker, forwards);
        return found == null ? -1 : found.index;
    }

    public record Match(long index, JsValue value) {
    }

    public static Match findMatch(ArrayLike target, List<JsValue> args, Invoker invoker, boolean forwards) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        for (var step = 0L; step < length; step++) {
            final var i = forwards ? step : length - 1 - step;
            final var element = target.get(i);
            if (JsCoercion.toBoolean(invoker.call(callback, self, List.of(element, new JsNumber(i), target.value())))) {
                return new Match(i, element);
            }
        }
        return null;
    }

    public static boolean some(ArrayLike target, List<JsValue> args, Invoker invoker) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        for (var i = 0L; i < length; i++) {
            if (target.has(i) && JsCoercion
                    .toBoolean(invoker.call(callback, self, List.of(target.get(i), new JsNumber(i), target.value())))) {
                return true;
            }
        }
        return false;
    }

    public static boolean every(ArrayLike target, List<JsValue> args, Invoker invoker) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        for (var i = 0L; i < length; i++) {
            if (target.has(i) && !JsCoercion
                    .toBoolean(invoker.call(callback, self, List.of(target.get(i), new JsNumber(i), target.value())))) {
                return false;
            }
        }
        return true;
    }

    public static boolean includes(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        if (length == 0) {
            return false;
        }
        final var search = arg(args, 0);
        final var from = toIntegerOrInfinity(arg(args, 1), ops);
        if (from == Double.POSITIVE_INFINITY) {
            return false;
        }
        for (var i = relativeIndex(from, length); i < length; i++) {
            if (SameValueZero.equal(target.get(i), search)) {
                return true;
            }
        }
        return false;
    }

    public static long indexOf(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        if (length == 0) {
            return -1;
        }
        final var search = arg(args, 0);
        final var from = toIntegerOrInfinity(arg(args, 1), ops);
        if (from == Double.POSITIVE_INFINITY) {
            return -1;
        }
        for (var i = relativeIndex(from, length); i < length; i++) {
            if (target.has(i) && JsOperators.strictEquals(target.get(i), search)) {
                return i;
            }
        }
        return -1;
    }

    public static long lastIndexOf(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        if (length == 0) {
            return -1;
        }
        final var search = arg(args, 0);
        final var from = args.size() > 1 ? toIntegerOrInfinity(args.get(1), ops) : (double) (length - 1);
        if (from == Double.NEGATIVE_INFINITY) {
            return -1;
        }
        final var start = from >= 0 ? (long) Math.min(from, (double) (length - 1)) : (long) (length + from);
        for (var i = start; i >= 0; i--) {
            if (target.has(i) && JsOperators.strictEquals(target.get(i), search)) {
                return i;
            }
        }
        return -1;
    }

    public static JsValue indexIterator(ArrayLike target, String kind) {
        final var exhausted = new boolean[1];
        return JsIterators.lazy(index -> {
            if (exhausted[0] || index >= target.length()) {
                exhausted[0] = true;
                return null;
            }
            return switch (kind) {
                case "keys" -> new JsNumber(index);
                case "entries" -> new JsArray(new ArrayList<>(List.of(new JsNumber(index), target.get(index))));
                default -> target.get(index);
            };
        });
    }

    private ArrayIteration() {
    }
}
