package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ArrayBuiltins {
    public static final List<String> NAMES = List.of("toLocaleString", "map", "filter", "reduce", "forEach", "find",
            "some", "every", "includes", "indexOf", "slice", "splice", "concat", "join", "toString", "push", "pop",
            "shift", "unshift", "sort", "flat", "findIndex", "findLast", "findLastIndex", "lastIndexOf", "reduceRight",
            "flatMap", "fill", "copyWithin", "reverse", "at", "keys", "values", "entries", "toReversed", "toSorted",
            "toSpliced", "with");

    private ArrayBuiltins() {
    }

    public static JsNativeFunction create(Invoker invoker, IterableToList iterableToList, EventLoop eventLoop,
            InterpreterOps ops) {
        final var array = new JsNativeFunction("Array", (_, args) -> construct(args));
        array.setProperty("isArray", new JsNativeFunction("isArray",
                (_, args) -> JsBoolean.of(!args.isEmpty() && args.getFirst() instanceof JsArray)));
        array.setProperty("from", new JsNativeFunction("from", (_, args) -> from(args, invoker, iterableToList)));
        array.setProperty("of", new JsNativeFunction("of", (_, args) -> new JsArray(new ArrayList<>(args))));
        array.setProperty("fromAsync", new JsNativeFunction("fromAsync", (_, args) -> AsyncIteratorBuiltins
                .drainToArray(ops, eventLoop, args.isEmpty() ? JsUndefined.getInstance() : args.getFirst())));
        return array;
    }

    private static JsValue from(List<JsValue> args, Invoker invoker, IterableToList iterableToList) {
        final var source = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var mapFn = args.size() > 1 && !(args.get(1) instanceof JsUndefined) ? args.get(1) : null;
        final List<JsValue> items;
        if (source instanceof JsArray array) {
            items = new ArrayList<>(array.getElements());
        } else if (source instanceof JsString string) {
            items = new ArrayList<>(InterpreterUtils.stringCodePoints(string.getValue()));
        } else {
            items = iterableToList.drain(source);
        }
        final var result = new JsArray();
        for (var i = 0; i < items.size(); i++) {
            final var element = items.get(i);
            result.push(mapFn == null
                    ? element
                    : invoker.call(mapFn, JsUndefined.getInstance(), List.of(element, new JsNumber(i))));
        }
        return result;
    }

    private static JsValue construct(List<JsValue> args) {
        if (args.size() == 1 && args.getFirst() instanceof JsNumber n) {
            final var result = new JsArray();
            final var length = (int) n.getValue();
            for (var i = 0; i < length; i++) {
                result.push(JsUndefined.getInstance());
            }
            return result;
        }
        return new JsArray(new ArrayList<>(args));
    }

    public static JsNativeFunction getMethod(JsArray receiver, String name, Invoker invoker, InterpreterOps ops) {
        return switch (name) {
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(toLocaleString(receiver, ops)));
            case "map" -> new JsNativeFunction("map", (_, args) -> map(receiver, args, invoker));
            case "filter" -> new JsNativeFunction("filter", (_, args) -> filter(receiver, args, invoker));
            case "reduce" -> new JsNativeFunction("reduce", (_, args) -> reduce(receiver, args, invoker));
            case "forEach" -> new JsNativeFunction("forEach", (_, args) -> forEach(receiver, args, invoker));
            case "find" -> new JsNativeFunction("find", (_, args) -> find(receiver, args, invoker));
            case "some" -> new JsNativeFunction("some", (_, args) -> JsBoolean.of(some(receiver, args, invoker)));
            case "every" -> new JsNativeFunction("every", (_, args) -> JsBoolean.of(every(receiver, args, invoker)));
            case "includes" ->
                new JsNativeFunction("includes", (_, args) -> JsBoolean.of(indexOf(receiver, args) >= 0));
            case "indexOf" -> new JsNativeFunction("indexOf", (_, args) -> new JsNumber(indexOf(receiver, args)));
            case "slice" -> new JsNativeFunction("slice", (_, args) -> slice(receiver, args));
            case "splice" -> new JsNativeFunction("splice", (_, args) -> splice(receiver, args));
            case "concat" -> new JsNativeFunction("concat", (_, args) -> concat(receiver, args, ops));
            case "join" -> new JsNativeFunction("join", (_, args) -> new JsString(join(receiver, args)));
            case "toString" -> new JsNativeFunction("toString", (_, _) -> new JsString(join(receiver, List.of())));
            case "push" -> new JsNativeFunction("push", (_, args) -> push(receiver, args));
            case "pop" -> new JsNativeFunction("pop", (_, _) -> pop(receiver));
            case "shift" -> new JsNativeFunction("shift", (_, _) -> shift(receiver));
            case "unshift" -> new JsNativeFunction("unshift", (_, args) -> unshift(receiver, args));
            case "sort" -> new JsNativeFunction("sort", (_, args) -> sort(receiver, args, invoker));
            case "flat" -> new JsNativeFunction("flat", (_, args) -> flat(receiver, args));
            case "findIndex" ->
                new JsNativeFunction("findIndex", (_, args) -> new JsNumber(findIndex(receiver, args, invoker)));
            case "findLast" -> new JsNativeFunction("findLast", (_, args) -> findLast(receiver, args, invoker));
            case "findLastIndex" -> new JsNativeFunction("findLastIndex",
                    (_, args) -> new JsNumber(findLastIndex(receiver, args, invoker)));
            case "lastIndexOf" ->
                new JsNativeFunction("lastIndexOf", (_, args) -> new JsNumber(lastIndexOf(receiver, args)));
            case "reduceRight" ->
                new JsNativeFunction("reduceRight", (_, args) -> reduceRight(receiver, args, invoker));
            case "flatMap" -> new JsNativeFunction("flatMap", (_, args) -> flatMap(receiver, args, invoker));
            case "fill" -> new JsNativeFunction("fill", (_, args) -> fill(receiver, args));
            case "copyWithin" -> new JsNativeFunction("copyWithin", (_, args) -> copyWithin(receiver, args));
            case "reverse" -> new JsNativeFunction("reverse", (_, _) -> reverse(receiver));
            case "at" -> new JsNativeFunction("at", (_, args) -> at(receiver, args));
            case "keys" -> new JsNativeFunction("keys", (_, _) -> keysIterator(receiver));
            case "values" -> new JsNativeFunction("values", (_, _) -> valuesIterator(receiver));
            case "entries" -> new JsNativeFunction("entries", (_, _) -> entriesIterator(receiver));
            case "toReversed" -> new JsNativeFunction("toReversed", (_, _) -> toReversed(receiver));
            case "toSorted" -> new JsNativeFunction("toSorted", (_, args) -> toSorted(receiver, args, invoker));
            case "toSpliced" -> new JsNativeFunction("toSpliced", (_, args) -> toSpliced(receiver, args));
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, args));
            default -> null;
        };
    }

    private static String toLocaleString(JsArray receiver, InterpreterOps ops) {
        final var sb = new StringBuilder();
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            final var element = elements.get(i);
            if (element instanceof JsNull || element instanceof JsUndefined) {
                continue;
            }
            final var method = ops.getMember(element, new JsString("toLocaleString"));
            if (method instanceof JsFunction || method instanceof JsNativeFunction) {
                sb.append(JsCoercion.toStr(ops.call(method, element, List.of())));
            } else {
                sb.append(JsCoercion.toStr(element));
            }
        }
        return sb.toString();
    }

    private static JsValue toReversed(JsArray receiver) {
        final var copy = new JsArray(receiver.getElements());
        return reverse(copy);
    }

    private static JsValue toSorted(JsArray receiver, List<JsValue> args, Invoker invoker) {
        return sort(new JsArray(receiver.getElements()), args, invoker);
    }

    private static JsValue toSpliced(JsArray receiver, List<JsValue> args) {
        final var copy = new JsArray(receiver.getElements());
        splice(copy, args);
        return copy;
    }

    private static JsValue with(JsArray receiver, List<JsValue> args) {
        final var elements = receiver.getElements();
        final var length = elements.size();
        var index = intArg(args, 0, 0);
        if (index < 0) {
            index += length;
        }
        if (index < 0 || index >= length) {
            throw new RangeErrorException("Invalid index : " + intArg(args, 0, 0));
        }
        final var copy = new JsArray(elements);
        copy.set(index, args.size() > 1 ? args.get(1) : JsUndefined.getInstance());
        return copy;
    }

    private static int findIndex(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
            if (!receiver.isHole(i) && JsCoercion.toBoolean(invoker.call(callback, JsUndefined.getInstance(),
                    List.of(elements.get(i), new JsNumber(i), receiver)))) {
                return i;
            }
        }
        return -1;
    }

    private static JsValue findLast(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var elements = receiver.getElements();
        for (var i = elements.size() - 1; i >= 0; i--) {
            if (receiver.isHole(i)) {
                continue;
            }
            final var element = elements.get(i);
            if (JsCoercion.toBoolean(
                    invoker.call(callback, JsUndefined.getInstance(), List.of(element, new JsNumber(i), receiver)))) {
                return element;
            }
        }
        return JsUndefined.getInstance();
    }

    private static int findLastIndex(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var elements = receiver.getElements();
        for (var i = elements.size() - 1; i >= 0; i--) {
            if (!receiver.isHole(i) && JsCoercion.toBoolean(invoker.call(callback, JsUndefined.getInstance(),
                    List.of(elements.get(i), new JsNumber(i), receiver)))) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(JsArray receiver, List<JsValue> args) {
        if (args.isEmpty()) {
            return -1;
        }
        final var target = args.getFirst();
        final var elements = receiver.getElements();
        for (var i = elements.size() - 1; i >= 0; i--) {
            if (!receiver.isHole(i) && JsOperators.strictEquals(elements.get(i), target)) {
                return i;
            }
        }
        return -1;
    }

    private static JsValue reduceRight(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var elements = receiver.getElements();
        var index = elements.size() - 1;
        JsValue accumulator;
        if (args.size() >= 2) {
            accumulator = args.get(1);
        } else if (elements.isEmpty()) {
            throw new TypeErrorException("Reduce of empty array with no initial value");
        } else {
            accumulator = elements.get(index);
            index--;
        }
        for (var i = index; i >= 0; i--) {
            if (receiver.isHole(i)) {
                continue;
            }
            accumulator = invoker.call(callback, JsUndefined.getInstance(),
                    List.of(accumulator, elements.get(i), new JsNumber(i), receiver));
        }
        return accumulator;
    }

    private static JsValue flatMap(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var elements = receiver.getElements();
        final var result = new JsArray();
        for (var i = 0; i < elements.size(); i++) {
            final var mapped = invoker.call(callback, JsUndefined.getInstance(),
                    List.of(elements.get(i), new JsNumber(i), receiver));
            if (mapped instanceof JsArray array) {
                for (final var element : array.getElements()) {
                    result.push(element);
                }
            } else {
                result.push(mapped);
            }
        }
        return result;
    }

    private static JsValue fill(JsArray receiver, List<JsValue> args) {
        final var elements = receiver.getElements();
        final var length = elements.size();
        final var value = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var start = clampIndex(intArg(args, 1, 0), length);
        final var end = args.size() < 3 || args.get(2) instanceof JsUndefined
                ? length
                : clampIndex(intArg(args, 2, length), length);
        for (var i = start; i < end; i++) {
            elements.set(i, value);
        }
        return receiver;
    }

    private static JsValue copyWithin(JsArray receiver, List<JsValue> args) {
        final var elements = receiver.getElements();
        final var length = elements.size();
        final var target = clampIndex(intArg(args, 0, 0), length);
        final var start = clampIndex(intArg(args, 1, 0), length);
        final var end = args.size() < 3 || args.get(2) instanceof JsUndefined
                ? length
                : clampIndex(intArg(args, 2, length), length);
        final var slice = new ArrayList<JsValue>();
        for (var i = start; i < end; i++) {
            slice.add(elements.get(i));
        }
        for (var i = 0; i < slice.size() && target + i < length; i++) {
            elements.set(target + i, slice.get(i));
        }
        return receiver;
    }

    private static JsValue reverse(JsArray receiver) {
        java.util.Collections.reverse(receiver.getElements());
        return receiver;
    }

    private static JsValue at(JsArray receiver, List<JsValue> args) {
        final var elements = receiver.getElements();
        var index = intArg(args, 0, 0);
        if (index < 0) {
            index += elements.size();
        }
        if (index < 0 || index >= elements.size()) {
            return JsUndefined.getInstance();
        }
        return elements.get(index);
    }

    private static JsValue keysIterator(JsArray receiver) {
        final var snapshot = new ArrayList<JsValue>();
        for (var i = 0; i < receiver.length(); i++) {
            snapshot.add(new JsNumber(i));
        }
        return JsIterators.of(snapshot.iterator());
    }

    private static JsValue valuesIterator(JsArray receiver) {
        return JsIterators.of(new ArrayList<>(receiver.getElements()).iterator());
    }

    private static JsValue entriesIterator(JsArray receiver) {
        final var snapshot = new ArrayList<JsValue>();
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
            snapshot.add(new JsArray(new ArrayList<>(List.of(new JsNumber(i), elements.get(i)))));
        }
        return JsIterators.of(snapshot.iterator());
    }

    private static JsValue map(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var result = new JsArray();
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
            if (receiver.isHole(i)) {
                result.pushHole();
                continue;
            }
            result.push(invoker.call(callback, JsUndefined.getInstance(),
                    List.of(elements.get(i), new JsNumber(i), receiver)));
        }
        return result;
    }

    private static JsValue filter(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var result = new JsArray();
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
            if (receiver.isHole(i)) {
                continue;
            }
            final var element = elements.get(i);
            if (JsCoercion.toBoolean(
                    invoker.call(callback, JsUndefined.getInstance(), List.of(element, new JsNumber(i), receiver)))) {
                result.push(element);
            }
        }
        return result;
    }

    private static JsValue reduce(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var elements = receiver.getElements();
        var index = 0;
        JsValue accumulator;
        if (args.size() >= 2) {
            accumulator = args.get(1);
        } else if (elements.isEmpty()) {
            throw new TypeErrorException("Reduce of empty array with no initial value");
        } else {
            accumulator = elements.getFirst();
            index = 1;
        }
        for (var i = index; i < elements.size(); i++) {
            if (receiver.isHole(i)) {
                continue;
            }
            accumulator = invoker.call(callback, JsUndefined.getInstance(),
                    List.of(accumulator, elements.get(i), new JsNumber(i), receiver));
        }
        return accumulator;
    }

    private static JsValue forEach(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
            if (receiver.isHole(i)) {
                continue;
            }
            invoker.call(callback, JsUndefined.getInstance(), List.of(elements.get(i), new JsNumber(i), receiver));
        }
        return JsUndefined.getInstance();
    }

    private static JsValue find(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
            if (receiver.isHole(i)) {
                continue;
            }
            final var element = elements.get(i);
            if (JsCoercion.toBoolean(
                    invoker.call(callback, JsUndefined.getInstance(), List.of(element, new JsNumber(i), receiver)))) {
                return element;
            }
        }
        return JsUndefined.getInstance();
    }

    private static boolean some(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
            if (!receiver.isHole(i) && JsCoercion.toBoolean(invoker.call(callback, JsUndefined.getInstance(),
                    List.of(elements.get(i), new JsNumber(i), receiver)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean every(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
            if (!receiver.isHole(i) && !JsCoercion.toBoolean(invoker.call(callback, JsUndefined.getInstance(),
                    List.of(elements.get(i), new JsNumber(i), receiver)))) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(JsArray receiver, List<JsValue> args) {
        if (args.isEmpty()) {
            return -1;
        }
        final var target = args.getFirst();
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
            if (!receiver.isHole(i) && JsOperators.strictEquals(elements.get(i), target)) {
                return i;
            }
        }
        return -1;
    }

    private static JsValue slice(JsArray receiver, List<JsValue> args) {
        final var elements = receiver.getElements();
        final var length = elements.size();
        final var start = clampIndex(intArg(args, 0, 0), length);
        final var end = args.size() < 2 || args.get(1) instanceof JsUndefined
                ? length
                : clampIndex(intArg(args, 1, length), length);
        final var result = new JsArray();
        for (var i = start; i < end; i++) {
            result.push(elements.get(i));
        }
        return result;
    }

    private static JsValue splice(JsArray receiver, List<JsValue> args) {
        final var elements = receiver.getElements();
        final var length = elements.size();
        final var start = clampIndex(intArg(args, 0, 0), length);
        final var deleteCount = args.size() < 2 ? length - start : Math.clamp(intArg(args, 1, 0), 0, length - start);
        final var removed = new JsArray();
        for (var i = 0; i < deleteCount; i++) {
            removed.push(elements.remove(start));
        }
        for (var i = args.size() - 1; i >= 2; i--) {
            elements.add(start, args.get(i));
        }
        return removed;
    }

    private static JsValue concat(JsArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var result = new JsArray(new ArrayList<>(receiver.getElements()));
        for (final var arg : args) {
            if (arg instanceof JsArray array) {
                for (final var element : array.getElements()) {
                    result.push(element);
                }
            } else if (isConcatSpreadable(arg, ops)) {
                for (final var key : ((JsObject) arg).keys()) {
                    result.push(((JsObject) arg).get(key));
                }
            } else {
                result.push(arg);
            }
        }
        return result;
    }

    private static boolean isConcatSpreadable(JsValue value, InterpreterOps ops) {
        return ops != null && value instanceof JsObject
                && JsCoercion.toBoolean(ops.getMember(value, JsSymbol.IS_CONCAT_SPREADABLE));
    }

    private static String join(JsArray receiver, List<JsValue> args) {
        final var separator = args.isEmpty() || args.getFirst() instanceof JsUndefined
                ? ","
                : JsCoercion.toStr(args.getFirst());
        final var sb = new StringBuilder();
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            final var element = elements.get(i);
            if (!(element instanceof JsUndefined) && !(element instanceof org.techhouse.simplejs.values.JsNull)) {
                sb.append(JsCoercion.toStr(element));
            }
        }
        return sb.toString();
    }

    private static JsValue push(JsArray receiver, List<JsValue> args) {
        for (final var arg : args) {
            receiver.push(arg);
        }
        return new JsNumber(receiver.length());
    }

    private static JsValue pop(JsArray receiver) {
        final var elements = receiver.getElements();
        if (elements.isEmpty()) {
            return JsUndefined.getInstance();
        }
        return elements.removeLast();
    }

    private static JsValue shift(JsArray receiver) {
        final var elements = receiver.getElements();
        if (elements.isEmpty()) {
            return JsUndefined.getInstance();
        }
        return elements.removeFirst();
    }

    private static JsValue unshift(JsArray receiver, List<JsValue> args) {
        for (var i = args.size() - 1; i >= 0; i--) {
            receiver.getElements().addFirst(args.get(i));
        }
        return new JsNumber(receiver.length());
    }

    private static JsValue sort(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var comparator = args.isEmpty() ? null : args.getFirst();
        final var holes = receiver.removeHoles();
        receiver.getElements().sort((left, right) -> {
            if (comparator instanceof JsNativeFunction
                    || comparator instanceof org.techhouse.simplejs.values.JsFunction) {
                final var result = JsCoercion
                        .toNumber(invoker.call(comparator, JsUndefined.getInstance(), List.of(left, right)));
                if (Double.isNaN(result) || result == 0) {
                    return 0;
                }
                return result < 0 ? -1 : 1;
            }
            return JsCoercion.toStr(left).compareTo(JsCoercion.toStr(right));
        });
        for (var i = 0; i < holes; i++) {
            receiver.pushHole();
        }
        return receiver;
    }

    private static JsValue flat(JsArray receiver, List<JsValue> args) {
        final var depth = args.isEmpty() ? 1 : intArg(args, 0, 1);
        final var result = new JsArray();
        flatInto(receiver, depth, result);
        return result;
    }

    private static void flatInto(JsArray source, int depth, JsArray target) {
        for (final var element : source.getElements()) {
            if (depth > 0 && element instanceof JsArray nested) {
                flatInto(nested, depth - 1, target);
            } else {
                target.push(element);
            }
        }
    }

    private static JsValue callback(List<JsValue> args) {
        if (args.isEmpty()) {
            throw new TypeErrorException("undefined is not a function");
        }
        return args.getFirst();
    }

    private static int clampIndex(int index, int length) {
        if (index < 0) {
            return Math.max(length + index, 0);
        }
        return Math.min(index, length);
    }

    private static int intArg(List<JsValue> args, int position, int fallback) {
        if (position >= args.size() || args.get(position) instanceof JsUndefined) {
            return fallback;
        }
        final var value = JsCoercion.toNumber(args.get(position));
        return Double.isNaN(value) ? 0 : (int) value;
    }
}
