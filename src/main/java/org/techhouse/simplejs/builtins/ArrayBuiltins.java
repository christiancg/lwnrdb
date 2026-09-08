package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.array.ArrayConstruct.construct;
import static org.techhouse.simplejs.builtins.array.ArrayConstruct.from;
import static org.techhouse.simplejs.builtins.array.ArrayConstruct.of;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.every;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.filter;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.find;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.findIndex;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.forEach;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.includes;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.indexIterator;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.indexOf;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.lastIndexOf;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.map;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.reduce;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.reduceRight;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.some;
import static org.techhouse.simplejs.builtins.array.ArrayIteration.toLocaleString;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.at;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.concat;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.copyWithin;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.fill;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.join;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.pop;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.push;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.reverse;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.shift;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.slice;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.splice;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.toStringMethod;
import static org.techhouse.simplejs.builtins.array.ArrayMutation.unshift;
import static org.techhouse.simplejs.builtins.array.ArraySort.flat;
import static org.techhouse.simplejs.builtins.array.ArraySort.flatMap;
import static org.techhouse.simplejs.builtins.array.ArraySort.sort;
import static org.techhouse.simplejs.builtins.array.ArraySort.toReversed;
import static org.techhouse.simplejs.builtins.array.ArraySort.toSorted;
import static org.techhouse.simplejs.builtins.array.ArraySort.toSpliced;
import static org.techhouse.simplejs.builtins.array.ArraySort.with;
import static org.techhouse.simplejs.values.JsLimits.MAX_SAFE_INTEGER_LONG;

import java.util.List;
import org.techhouse.simplejs.builtins.array.ArrayLike;
import org.techhouse.simplejs.builtins.iterator.ArrayFromAsync;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ArrayBuiltins {
    public static final List<String> NAMES = List.of("toLocaleString", "map", "filter", "reduce", "forEach", "find",
            "some", "every", "includes", "indexOf", "slice", "splice", "concat", "join", "toString", "push", "pop",
            "shift", "unshift", "sort", "flat", "findIndex", "findLast", "findLastIndex", "lastIndexOf", "reduceRight",
            "flatMap", "fill", "copyWithin", "reverse", "at", "keys", "values", "entries", "toReversed", "toSorted",
            "toSpliced", "with");

    public static final JsString LENGTH = new JsString("length");
    public static final JsString NEXT = new JsString("next");
    public static final JsString DONE = new JsString("done");
    public static final JsString VALUE = new JsString("value");
    public static final JsString RETURN = new JsString("return");
    public static final JsString TO_STRING = new JsString("toString");

    private ArrayBuiltins() {
    }

    public static JsNativeFunction create(Invoker invoker, EventLoop eventLoop, InterpreterOps ops,
            Intrinsics intrinsics) {
        final var array = new JsNativeFunction("Array", (thisArg, args) -> construct(thisArg, args, ops));
        array.setProperty("isArray",
                new JsNativeFunction("isArray", (_, args) -> JsBoolean.of(isArray(arg(args, 0), intrinsics))));
        array.setProperty("from", new JsNativeFunction("from", (receiver, args) -> from(receiver, args, invoker, ops)));
        array.setProperty("of", new JsNativeFunction("of", (receiver, args) -> of(receiver, args, ops)));
        final var fromAsync = new JsNativeFunction("fromAsync",
                (receiver, args) -> ArrayFromAsync.fromAsync(ops, eventLoop, receiver, args));
        fromAsync.setLength(1);
        array.setProperty("fromAsync", fromAsync);
        return array;
    }

    public static boolean isArray(JsValue value) {
        if (value instanceof JsProxy proxy) {
            if (proxy.isRevoked()) {
                throw new TypeErrorException("Cannot perform 'IsArray' on a proxy that has been revoked");
            }
            return isArray(proxy.getTarget());
        }
        if (value instanceof JsArray) {
            return true;
        }
        return value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsArray;
    }

    public static boolean isArray(JsValue value, Intrinsics intrinsics) {
        return isArray(value) || (intrinsics != null && value == intrinsics.arrayProto);
    }

    public static JsNativeFunction getMethod(JsValue receiver, String name, Invoker invoker, InterpreterOps ops) {
        return switch (name) {
            case "toLocaleString" ->
                new JsNativeFunction(name, (_, _) -> new JsString(toLocaleString(target(receiver, ops), invoker, ops)));
            case "map" -> new JsNativeFunction(name, (_, args) -> map(target(receiver, ops), args, invoker, ops));
            case "filter" -> new JsNativeFunction(name, (_, args) -> filter(target(receiver, ops), args, invoker, ops));
            case "reduce" -> new JsNativeFunction(name, (_, args) -> reduce(target(receiver, ops), args, invoker));
            case "forEach" -> new JsNativeFunction(name, (_, args) -> forEach(target(receiver, ops), args, invoker));
            case "find" -> new JsNativeFunction(name, (_, args) -> find(target(receiver, ops), args, invoker, true));
            case "some" ->
                new JsNativeFunction(name, (_, args) -> JsBoolean.of(some(target(receiver, ops), args, invoker)));
            case "every" ->
                new JsNativeFunction(name, (_, args) -> JsBoolean.of(every(target(receiver, ops), args, invoker)));
            case "includes" ->
                new JsNativeFunction(name, (_, args) -> JsBoolean.of(includes(target(receiver, ops), args, ops)));
            case "indexOf" ->
                new JsNativeFunction(name, (_, args) -> new JsNumber(indexOf(target(receiver, ops), args, ops)));
            case "slice" -> new JsNativeFunction(name, (_, args) -> slice(target(receiver, ops), args, ops));
            case "splice" -> new JsNativeFunction(name, (_, args) -> splice(target(receiver, ops), args, ops));
            case "concat" -> new JsNativeFunction(name, (_, args) -> concat(target(receiver, ops), args, ops));
            case "join" ->
                new JsNativeFunction(name, (_, args) -> new JsString(join(target(receiver, ops), args, ops)));
            case "toString" ->
                new JsNativeFunction(name, (_, _) -> toStringMethod(target(receiver, ops), invoker, ops));
            case "push" -> new JsNativeFunction(name, (_, args) -> push(target(receiver, ops), args));
            case "pop" -> new JsNativeFunction(name, (_, _) -> pop(target(receiver, ops)));
            case "shift" -> new JsNativeFunction(name, (_, _) -> shift(target(receiver, ops)));
            case "unshift" -> new JsNativeFunction(name, (_, args) -> unshift(target(receiver, ops), args));
            case "sort" -> new JsNativeFunction(name, (_, args) -> sort(target(receiver, ops), args, invoker, ops));
            case "flat" -> new JsNativeFunction(name, (_, args) -> flat(target(receiver, ops), args, ops));
            case "findIndex" -> new JsNativeFunction(name,
                    (_, args) -> new JsNumber(findIndex(target(receiver, ops), args, invoker, true)));
            case "findLast" ->
                new JsNativeFunction(name, (_, args) -> find(target(receiver, ops), args, invoker, false));
            case "findLastIndex" -> new JsNativeFunction(name,
                    (_, args) -> new JsNumber(findIndex(target(receiver, ops), args, invoker, false)));
            case "lastIndexOf" ->
                new JsNativeFunction(name, (_, args) -> new JsNumber(lastIndexOf(target(receiver, ops), args, ops)));
            case "reduceRight" ->
                new JsNativeFunction(name, (_, args) -> reduceRight(target(receiver, ops), args, invoker));
            case "flatMap" ->
                new JsNativeFunction(name, (_, args) -> flatMap(target(receiver, ops), args, invoker, ops));
            case "fill" -> new JsNativeFunction(name, (_, args) -> fill(target(receiver, ops), args, ops));
            case "copyWithin" -> new JsNativeFunction(name, (_, args) -> copyWithin(target(receiver, ops), args, ops));
            case "reverse" -> new JsNativeFunction(name, (_, _) -> reverse(target(receiver, ops)));
            case "at" -> new JsNativeFunction(name, (_, args) -> at(target(receiver, ops), args, ops));
            case "keys" -> new JsNativeFunction(name, (_, _) -> indexIterator(target(receiver, ops), "keys"));
            case "values" -> new JsNativeFunction(name, (_, _) -> indexIterator(target(receiver, ops), "values"));
            case "entries" -> new JsNativeFunction(name, (_, _) -> indexIterator(target(receiver, ops), "entries"));
            case "toReversed" -> new JsNativeFunction(name, (_, _) -> toReversed(target(receiver, ops), ops));
            case "toSorted" ->
                new JsNativeFunction(name, (_, args) -> toSorted(target(receiver, ops), args, invoker, ops));
            case "toSpliced" -> new JsNativeFunction(name, (_, args) -> toSpliced(target(receiver, ops), args, ops));
            case "with" -> new JsNativeFunction(name, (_, args) -> with(target(receiver, ops), args, ops));
            default -> null;
        };
    }

    public static ArrayLike target(JsValue receiver, InterpreterOps ops) {
        return new ArrayLike(receiver, ops);
    }

    public static JsString key(long index) {
        return new JsString(Long.toString(index));
    }

    public static long toLength(JsValue value, InterpreterOps ops) {
        final var number = toIntegerOrInfinity(value, ops);
        if (number <= 0) {
            return 0;
        }
        return (long) Math.min(number, (double) MAX_SAFE_INTEGER_LONG);
    }

    public static double toIntegerOrInfinity(JsValue value, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number)) {
            return 0;
        }
        if (Double.isInfinite(number)) {
            return number;
        }
        return number < 0 ? Math.ceil(number) : Math.floor(number);
    }

    public static long relativeIndex(double relative, long length) {
        if (relative < 0) {
            return relative == Double.NEGATIVE_INFINITY ? 0 : (long) Math.max(length + relative, 0);
        }
        return (long) Math.min(relative, (double) length);
    }

    public static JsValue arg(List<JsValue> args, int position) {
        return position < args.size() ? args.get(position) : JsUndefined.getInstance();
    }

    public static JsValue thisArg(List<JsValue> args) {
        return args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
    }
}
