package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ArrayBuiltins {
    private ArrayBuiltins() {
    }

    public static JsNativeFunction create() {
        final var array = new JsNativeFunction("Array", (_, args) -> construct(args));
        array.setProperty("isArray", new JsNativeFunction("isArray",
                (_, args) -> JsBoolean.of(!args.isEmpty() && args.getFirst() instanceof JsArray)));
        return array;
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

    public static JsNativeFunction getMethod(JsArray receiver, String name, Invoker invoker) {
        return switch (name) {
            case "map" -> new JsNativeFunction("map", (_, args) -> map(receiver, args, invoker));
            case "filter" -> new JsNativeFunction("filter", (_, args) -> filter(receiver, args, invoker));
            case "reduce" -> new JsNativeFunction("reduce", (_, args) -> reduce(receiver, args, invoker));
            case "forEach" -> new JsNativeFunction("forEach", (_, args) -> forEach(receiver, args, invoker));
            case "find" -> new JsNativeFunction("find", (_, args) -> find(receiver, args, invoker));
            case "some" -> new JsNativeFunction("some", (_, args) -> JsBoolean.of(some(receiver, args, invoker)));
            case "every" ->
                new JsNativeFunction("every", (_, args) -> JsBoolean.of(every(receiver, args, invoker)));
            case "includes" ->
                new JsNativeFunction("includes", (_, args) -> JsBoolean.of(indexOf(receiver, args) >= 0));
            case "indexOf" -> new JsNativeFunction("indexOf", (_, args) -> new JsNumber(indexOf(receiver, args)));
            case "slice" -> new JsNativeFunction("slice", (_, args) -> slice(receiver, args));
            case "splice" -> new JsNativeFunction("splice", (_, args) -> splice(receiver, args));
            case "concat" -> new JsNativeFunction("concat", (_, args) -> concat(receiver, args));
            case "join" -> new JsNativeFunction("join", (_, args) -> new JsString(join(receiver, args)));
            case "push" -> new JsNativeFunction("push", (_, args) -> push(receiver, args));
            case "pop" -> new JsNativeFunction("pop", (_, _) -> pop(receiver));
            case "shift" -> new JsNativeFunction("shift", (_, _) -> shift(receiver));
            case "unshift" -> new JsNativeFunction("unshift", (_, args) -> unshift(receiver, args));
            case "sort" -> new JsNativeFunction("sort", (_, args) -> sort(receiver, args, invoker));
            case "flat" -> new JsNativeFunction("flat", (_, args) -> flat(receiver, args));
            default -> null;
        };
    }

    private static JsValue map(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var result = new JsArray();
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
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
            accumulator = invoker.call(callback, JsUndefined.getInstance(),
                    List.of(accumulator, elements.get(i), new JsNumber(i), receiver));
        }
        return accumulator;
    }

    private static JsValue forEach(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
            invoker.call(callback, JsUndefined.getInstance(), List.of(elements.get(i), new JsNumber(i), receiver));
        }
        return JsUndefined.getInstance();
    }

    private static JsValue find(JsArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var elements = receiver.getElements();
        for (var i = 0; i < elements.size(); i++) {
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
            if (JsCoercion.toBoolean(invoker.call(callback, JsUndefined.getInstance(),
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
            if (!JsCoercion.toBoolean(invoker.call(callback, JsUndefined.getInstance(),
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
            if (JsOperators.strictEquals(elements.get(i), target)) {
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
        final var deleteCount = args.size() < 2
                ? length - start
                : Math.clamp(intArg(args, 1, 0), 0, length - start);
        final var removed = new JsArray();
        for (var i = 0; i < deleteCount; i++) {
            removed.push(elements.remove(start));
        }
        for (var i = args.size() - 1; i >= 2; i--) {
            elements.add(start, args.get(i));
        }
        return removed;
    }

    private static JsValue concat(JsArray receiver, List<JsValue> args) {
        final var result = new JsArray(new ArrayList<>(receiver.getElements()));
        for (final var arg : args) {
            if (arg instanceof JsArray array) {
                for (final var element : array.getElements()) {
                    result.push(element);
                }
            } else {
                result.push(arg);
            }
        }
        return result;
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
