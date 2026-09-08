package org.techhouse.simplejs.builtins.typedarray;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.BuiltinArgs.callback;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.asTypedArray;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.intArg;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.validate;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.at;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.copyWithin;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.fill;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.fillCreated;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.reverse;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.set;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.slice;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.sort;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.speciesCreate;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.subarray;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.toReversed;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.toSorted;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayMutation.with;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Invoker;
import org.techhouse.simplejs.builtins.JsIterators;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.SameValueZero;

public final class TypedArrayIteration {
    public interface Step {
        JsValue at(JsTypedArray receiver, int index);
    }

    public static JsValue getMethod(JsTypedArray receiver, String name, Invoker invoker, InterpreterOps ops) {
        return switch (name) {
            case "forEach" -> new JsNativeFunction("forEach", (_, args) -> forEach(validate(receiver), args, invoker));
            case "map" -> new JsNativeFunction("map", (_, args) -> map(validate(receiver), args, invoker, ops));
            case "filter" ->
                new JsNativeFunction("filter", (_, args) -> filter(validate(receiver), args, invoker, ops));
            case "reduce" ->
                new JsNativeFunction("reduce", (_, args) -> reduce(validate(receiver), args, invoker, false));
            case "reduceRight" ->
                new JsNativeFunction("reduceRight", (_, args) -> reduce(validate(receiver), args, invoker, true));
            case "find" -> new JsNativeFunction("find", (_, args) -> find(validate(receiver), args, invoker, false));
            case "findIndex" ->
                new JsNativeFunction("findIndex", (_, args) -> find(validate(receiver), args, invoker, true));
            case "some" ->
                new JsNativeFunction("some", (_, args) -> JsBoolean.of(some(validate(receiver), args, invoker, false)));
            case "every" ->
                new JsNativeFunction("every", (_, args) -> JsBoolean.of(some(validate(receiver), args, invoker, true)));
            case "indexOf" -> new JsNativeFunction("indexOf",
                    (_, args) -> new JsNumber(indexOf(validate(receiver), args, false, ops)));
            case "lastIndexOf" -> new JsNativeFunction("lastIndexOf",
                    (_, args) -> new JsNumber(indexOf(validate(receiver), args, true, ops)));
            case "toLocaleString" -> new JsNativeFunction("toLocaleString",
                    (_, _) -> new JsString(toLocaleString(validate(receiver), invoker, ops)));
            case "includes" ->
                new JsNativeFunction("includes", (_, args) -> JsBoolean.of(includes(validate(receiver), args, ops)));
            case "join" -> new JsNativeFunction("join", (_, args) -> new JsString(join(validate(receiver), args, ops)));
            case "slice" -> new JsNativeFunction("slice", (_, args) -> slice(validate(receiver), args, ops));
            case "subarray" -> new JsNativeFunction("subarray", (_, args) -> subarray(receiver, args, ops));
            case "set" -> new JsNativeFunction("set", (_, args) -> set(receiver, args, ops));
            case "fill" -> new JsNativeFunction("fill", (_, args) -> fill(validate(receiver), args, ops));
            case "reverse" -> new JsNativeFunction("reverse", (_, _) -> reverse(validate(receiver)));
            case "at" -> new JsNativeFunction("at", (_, args) -> at(validate(receiver), args, ops));
            case "toString" ->
                new JsNativeFunction("toString", (_, _) -> new JsString(join(validate(receiver), List.of(), ops)));
            case "keys" -> new JsNativeFunction("keys",
                    (_, _) -> liveIterator(validate(receiver), (_, index) -> new JsNumber(index)));
            case "values" ->
                new JsNativeFunction("values", (_, _) -> liveIterator(validate(receiver), JsTypedArray::getElement));
            case "entries" -> new JsNativeFunction("entries", (_, _) -> liveIterator(validate(receiver), (view,
                    index) -> new JsArray(new ArrayList<>(List.of(new JsNumber(index), view.getElement(index))))));
            case "sort" -> new JsNativeFunction("sort", (_, args) -> sort(receiver, args, invoker, ops));
            case "toSorted" -> new JsNativeFunction("toSorted", (_, args) -> toSorted(receiver, args, invoker, ops));
            case "toReversed" -> new JsNativeFunction("toReversed", (_, _) -> toReversed(validate(receiver), ops));
            case "with" -> new JsNativeFunction("with", (_, args) -> with(validate(receiver), args, ops));
            case "findLast" ->
                new JsNativeFunction("findLast", (_, args) -> findLast(validate(receiver), args, invoker, false));
            case "findLastIndex" ->
                new JsNativeFunction("findLastIndex", (_, args) -> findLast(validate(receiver), args, invoker, true));
            case "copyWithin" ->
                new JsNativeFunction("copyWithin", (_, args) -> copyWithin(validate(receiver), args, ops));
            default -> null;
        };
    }

    public static JsValue findLast(JsTypedArray receiver, List<JsValue> args, Invoker invoker, boolean wantIndex) {
        final var callback = callback(args);
        final var thisArg = arg(args, 1);
        for (var i = receiver.length() - 1; i >= 0; i--) {
            final var element = receiver.getElement(i);
            if (JsCoercion.toBoolean(invoker.call(callback, thisArg, List.of(element, new JsNumber(i), receiver)))) {
                return wantIndex ? new JsNumber(i) : element;
            }
        }
        return wantIndex ? new JsNumber(-1) : JsUndefined.getInstance();
    }

    public static JsValue forEach(JsTypedArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var thisArg = arg(args, 1);
        final var length = receiver.length();
        for (var i = 0; i < length; i++) {
            invoker.call(callback, thisArg, List.of(receiver.getElement(i), new JsNumber(i), receiver));
        }
        return JsUndefined.getInstance();
    }

    public static JsValue map(JsTypedArray receiver, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var callback = callback(args);
        final var thisArg = arg(args, 1);
        final var length = receiver.length();
        final var created = speciesCreate(receiver, List.of(new JsNumber(length)), ops);
        final var result = asTypedArray(created);
        for (var i = 0; i < length; i++) {
            result.setElement(i,
                    invoker.call(callback, thisArg, List.of(receiver.getElement(i), new JsNumber(i), receiver)), ops);
        }
        return created;
    }

    public static JsValue filter(JsTypedArray receiver, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var callback = callback(args);
        final var thisArg = arg(args, 1);
        final var length = receiver.length();
        final var kept = new ArrayList<JsValue>();
        for (var i = 0; i < length; i++) {
            final var element = receiver.getElement(i);
            if (JsCoercion.toBoolean(invoker.call(callback, thisArg, List.of(element, new JsNumber(i), receiver)))) {
                kept.add(element);
            }
        }
        return fillCreated(receiver, kept, ops);
    }

    public static JsValue reduce(JsTypedArray receiver, List<JsValue> args, Invoker invoker, boolean right) {
        final var callback = callback(args);
        final var hasInitial = args.size() > 1;
        var accumulator = hasInitial ? args.get(1) : JsUndefined.getInstance();
        final var length = receiver.length();
        var started = hasInitial;
        for (var step = 0; step < length; step++) {
            final var i = right ? length - 1 - step : step;
            if (!started) {
                accumulator = receiver.getElement(i);
                started = true;
                continue;
            }
            accumulator = invoker.call(callback, JsUndefined.getInstance(),
                    List.of(accumulator, receiver.getElement(i), new JsNumber(i), receiver));
        }
        if (!started) {
            throw new TypeErrorException("Reduce of empty array with no initial value");
        }
        return accumulator;
    }

    public static JsValue find(JsTypedArray receiver, List<JsValue> args, Invoker invoker, boolean wantIndex) {
        final var callback = callback(args);
        final var thisArg = arg(args, 1);
        final var length = receiver.length();
        for (var i = 0; i < length; i++) {
            final var element = receiver.getElement(i);
            if (JsCoercion.toBoolean(invoker.call(callback, thisArg, List.of(element, new JsNumber(i), receiver)))) {
                return wantIndex ? new JsNumber(i) : element;
            }
        }
        return wantIndex ? new JsNumber(-1) : JsUndefined.getInstance();
    }

    public static boolean some(JsTypedArray receiver, List<JsValue> args, Invoker invoker, boolean every) {
        final var callback = callback(args);
        final var thisArg = arg(args, 1);
        final var length = receiver.length();
        for (var i = 0; i < length; i++) {
            final var matched = JsCoercion.toBoolean(
                    invoker.call(callback, thisArg, List.of(receiver.getElement(i), new JsNumber(i), receiver)));
            if (every && !matched) {
                return false;
            }
            if (!every && matched) {
                return true;
            }
        }
        return every;
    }

    public static int indexOf(JsTypedArray receiver, List<JsValue> args, boolean last, InterpreterOps ops) {
        final var target = arg(args, 0);
        final var length = receiver.length();
        if (length == 0) {
            return -1;
        }
        final var bounds = searchBounds(args, length, last, ops);
        for (var i = bounds[0]; last ? i >= bounds[1] : i <= bounds[1]; i += last ? -1 : 1) {
            if (sameNumber(receiver.getElement(i), target)) {
                return i;
            }
        }
        return -1;
    }

    public static int[] searchBounds(List<JsValue> args, int length, boolean last, InterpreterOps ops) {
        final var provided = args.size() > 1;
        if (last) {
            var from = provided ? (int) toInteger(JsCoercion.toNumber(args.get(1), ops)) : length - 1;
            if (from < 0) {
                from += length;
            }
            return new int[]{Math.min(from, length - 1), 0};
        }
        var from = provided ? (int) toInteger(JsCoercion.toNumber(args.get(1), ops)) : 0;
        if (from < 0) {
            from = Math.max(length + from, 0);
        }
        return new int[]{from, length - 1};
    }

    public static double toInteger(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    public static boolean includes(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var target = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var length = receiver.length();
        if (length == 0) {
            return false;
        }
        var from = (int) toInteger(intArg(args, 1, 0, ops));
        from = from < 0 ? Math.max(length + from, 0) : Math.min(from, length);
        for (var i = from; i < length; i++) {
            if (SameValueZero.equal(receiver.getElement(i), target)) {
                return true;
            }
        }
        return false;
    }

    public static boolean sameNumber(JsValue a, JsValue b) {
        if (a instanceof JsBigInt x) {
            return b instanceof JsBigInt y && x.getValue().equals(y.getValue());
        }
        return a instanceof JsNumber x && b instanceof JsNumber y && x.getValue() == y.getValue();
    }

    public static String join(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var length = receiver.length();
        final var separator = args.isEmpty() || args.getFirst() instanceof JsUndefined
                ? ","
                : JsCoercion.toStr(args.getFirst(), ops);
        final var sb = new StringBuilder();
        for (var i = 0; i < length; i++) {
            if (i > 0) {
                sb.append(separator);
            }
            final var element = receiver.getElement(i);
            if (!(element instanceof JsUndefined)) {
                sb.append(JsCoercion.toStr(element));
            }
        }
        return sb.toString();
    }

    public static String toLocaleString(JsTypedArray receiver, Invoker invoker, InterpreterOps ops) {
        final var length = receiver.length();
        final var sb = new StringBuilder();
        for (var i = 0; i < length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            final var element = receiver.getElement(i);
            if (element instanceof JsUndefined) {
                continue;
            }
            final var method = ops == null ? null : ops.getMember(element, new JsString("toLocaleString"));
            if (InterpreterUtils.isCallable(method)) {
                sb.append(JsCoercion.toStr(invoker.call(method, element, List.of()), ops));
            } else {
                sb.append(JsCoercion.toStr(element, ops));
            }
        }
        return sb.toString();
    }

    public static JsObject liveIterator(JsTypedArray receiver, Step step) {
        return JsIterators.of(new java.util.Iterator<>() {
            private int index;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                if (!exhausted && receiver.isOutOfBounds()) {
                    throw new TypeErrorException("Cannot iterate a typed array over a detached buffer");
                }
                if (!exhausted && index >= receiver.length()) {
                    exhausted = true;
                }
                return !exhausted;
            }

            @Override
            public JsValue next() {
                return step.at(receiver, index++);
            }
        });
    }

    private TypedArrayIteration() {
    }
}
