package org.techhouse.simplejs.builtins.array;

import static org.techhouse.simplejs.builtins.ArrayBuiltins.isArray;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.relativeIndex;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.toIntegerOrInfinity;
import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.array.ArrayConstruct.createDataPropertyOrThrow;
import static org.techhouse.simplejs.builtins.array.ArrayConstruct.setResultLength;
import static org.techhouse.simplejs.builtins.array.ArrayConstruct.speciesCreate;
import static org.techhouse.simplejs.values.JsLimits.MAX_SAFE_INTEGER_LONG;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Invoker;
import org.techhouse.simplejs.builtins.ObjectProtoBuiltins;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.PropertyDescriptor;

public final class ArrayMutation {
    public static JsValue slice(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        final var start = relativeIndex(toIntegerOrInfinity(arg(args, 0), ops), length);
        final var end = args.size() < 2 || args.get(1) instanceof JsUndefined
                ? length
                : relativeIndex(toIntegerOrInfinity(args.get(1), ops), length);
        final var count = Math.max(end - start, 0);
        final var result = speciesCreate(target, count, ops);
        var written = 0L;
        for (var i = start; i < end; i++) {
            if (target.has(i)) {
                createDataPropertyOrThrow(result, written, target.get(i), ops);
            }
            written++;
        }
        setResultLength(result, written, ops);
        return result;
    }

    public static JsValue splice(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        final var start = relativeIndex(toIntegerOrInfinity(arg(args, 0), ops), length);
        final var insertCount = Math.max(args.size() - 2, 0);
        final long deleteCount;
        if (args.isEmpty()) {
            deleteCount = 0;
        } else if (args.size() == 1) {
            deleteCount = length - start;
        } else {
            deleteCount = (long) Math.clamp(toIntegerOrInfinity(args.get(1), ops), 0, (double) (length - start));
        }
        if (length + insertCount - deleteCount > MAX_SAFE_INTEGER_LONG) {
            throw new TypeErrorException("Invalid array length");
        }
        final var removed = speciesCreate(target, deleteCount, ops);
        for (var i = 0L; i < deleteCount; i++) {
            if (target.has(start + i)) {
                createDataPropertyOrThrow(removed, i, target.get(start + i), ops);
            }
        }
        setResultLength(removed, deleteCount, ops);
        shiftForSplice(target, length, start, deleteCount, insertCount);
        for (var i = 0; i < insertCount; i++) {
            target.set(start + i, args.get(i + 2));
        }
        target.setLength(length - deleteCount + insertCount);
        return removed;
    }

    public static void shiftForSplice(ArrayLike target, long length, long start, long deleteCount, long insertCount) {
        if (insertCount < deleteCount) {
            for (var i = start; i < length - deleteCount; i++) {
                moveOrDelete(target, i + deleteCount, i + insertCount);
            }
            for (var i = length; i > length - deleteCount + insertCount; i--) {
                target.delete(i - 1);
            }
        } else if (insertCount > deleteCount) {
            for (var i = length - deleteCount; i > start; i--) {
                moveOrDelete(target, i + deleteCount - 1, i + insertCount - 1);
            }
        }
    }

    public static void moveOrDelete(ArrayLike target, long from, long to) {
        if (target.has(from)) {
            target.set(to, target.get(from));
        } else {
            target.delete(to);
        }
    }

    public static JsValue concat(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var result = speciesCreate(target, 0, ops);
        var written = 0L;
        final var items = new ArrayList<JsValue>(args.size() + 1);
        items.add(target.value());
        items.addAll(args);
        for (final var item : items) {
            if (isConcatSpreadable(item, ops)) {
                final var source = new ArrayLike(item, ops);
                final var length = source.length();
                if (written + length > MAX_SAFE_INTEGER_LONG) {
                    throw new TypeErrorException("Invalid array length");
                }
                for (var i = 0L; i < length; i++) {
                    if (source.has(i)) {
                        createDataPropertyOrThrow(result, written, source.get(i), ops);
                    }
                    written++;
                }
            } else {
                InterpreterOps.chargeElements(ops, 1);
                if (written >= MAX_SAFE_INTEGER_LONG) {
                    throw new TypeErrorException("Invalid array length");
                }
                createDataPropertyOrThrow(result, written, item, ops);
                written++;
            }
        }
        setResultLength(result, written, ops);
        return result;
    }

    public static boolean isConcatSpreadable(JsValue value, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(value)) {
            return false;
        }
        if (ops != null) {
            final var flag = concatSpreadableFlag(value, ops);
            if (!(flag instanceof JsUndefined)) {
                return JsCoercion.toBoolean(flag);
            }
        }
        return isArray(value);
    }

    public static JsValue concatSpreadableFlag(JsValue value, InterpreterOps ops) {
        if (value instanceof JsObject || value instanceof JsProxy) {
            return ops.getMember(value, JsSymbol.IS_CONCAT_SPREADABLE);
        }
        PropertyDescriptor found = null;
        for (var link = value; found == null && InterpreterUtils.isObjectLike(link); link = ops.getPrototypeOf(link)) {
            found = link.getOwnProperty(JsSymbol.IS_CONCAT_SPREADABLE);
        }
        if (found == null) {
            return JsUndefined.getInstance();
        }
        if (!found.isAccessorDescriptor()) {
            return found.value();
        }
        return InterpreterUtils.isCallable(found.getter())
                ? ops.call(found.getter(), value, List.of())
                : JsUndefined.getInstance();
    }

    public static String join(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        var charged = 0;
        final var separator = args.isEmpty() || args.getFirst() instanceof JsUndefined
                ? ","
                : JsCoercion.toStr(args.getFirst(), ops);
        final var sb = new StringBuilder();
        for (var i = 0L; i < length; i++) {
            if (i > 0) {
                sb.append(separator);
            }
            final var element = target.get(i);
            if (!(element instanceof JsUndefined) && !(element instanceof JsNull)) {
                sb.append(JsCoercion.toStr(element, ops));
            }
            InterpreterOps.chargeChars(ops, sb.length() - charged);
            charged = sb.length();
        }
        return sb.toString();
    }

    public static JsValue toStringMethod(ArrayLike target, Invoker invoker, InterpreterOps ops) {
        final var join = target.getKey(new JsString("join"));
        if (InterpreterUtils.isCallable(join)) {
            return invoker.call(join, target.value(), List.of());
        }
        return invoker.call(ObjectProtoBuiltins.getMethod(target.value(), "toString", ops, null), target.value(),
                List.of());
    }

    public static JsValue push(ArrayLike target, List<JsValue> args) {
        var length = target.length();
        if (length + args.size() > MAX_SAFE_INTEGER_LONG) {
            throw new TypeErrorException("Invalid array length");
        }
        for (final var element : args) {
            target.set(length, element);
            length++;
        }
        target.setLength(length);
        return new JsNumber(length);
    }

    public static JsValue pop(ArrayLike target) {
        final var length = target.length();
        if (length == 0) {
            target.setLength(0);
            return JsUndefined.getInstance();
        }
        final var element = target.get(length - 1);
        target.delete(length - 1);
        target.setLength(length - 1);
        return element;
    }

    public static JsValue shift(ArrayLike target) {
        final var length = target.length();
        if (length == 0) {
            target.setLength(0);
            return JsUndefined.getInstance();
        }
        final var first = target.get(0);
        for (var i = 1L; i < length; i++) {
            moveOrDelete(target, i, i - 1);
        }
        target.delete(length - 1);
        target.setLength(length - 1);
        return first;
    }

    public static JsValue unshift(ArrayLike target, List<JsValue> args) {
        final var length = target.length();
        final var count = args.size();
        if (count > 0) {
            if (length + count > MAX_SAFE_INTEGER_LONG) {
                throw new TypeErrorException("Invalid array length");
            }
            for (var i = length; i > 0; i--) {
                moveOrDelete(target, i - 1, i + count - 1);
            }
            for (var i = 0; i < count; i++) {
                target.set(i, args.get(i));
            }
        }
        target.setLength(length + count);
        return new JsNumber(length + count);
    }

    public static JsValue reverse(ArrayLike target) {
        final var length = target.length();
        for (var lower = 0L; lower < length / 2; lower++) {
            final var upper = length - lower - 1;
            final var lowerExists = target.has(lower);
            final var lowerValue = lowerExists ? target.get(lower) : null;
            final var upperExists = target.has(upper);
            final var upperValue = upperExists ? target.get(upper) : null;
            if (lowerExists && upperExists) {
                target.set(lower, upperValue);
                target.set(upper, lowerValue);
            } else if (upperExists) {
                target.set(lower, upperValue);
                target.delete(upper);
            } else if (lowerExists) {
                target.delete(lower);
                target.set(upper, lowerValue);
            }
        }
        return target.value();
    }

    public static JsValue fill(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        final var value = arg(args, 0);
        final var start = relativeIndex(toIntegerOrInfinity(arg(args, 1), ops), length);
        final var end = args.size() < 3 || args.get(2) instanceof JsUndefined
                ? length
                : relativeIndex(toIntegerOrInfinity(args.get(2), ops), length);
        for (var i = start; i < end; i++) {
            InterpreterOps.tick(ops);
            target.set(i, value);
        }
        return target.value();
    }

    public static JsValue copyWithin(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        var to = relativeIndex(toIntegerOrInfinity(arg(args, 0), ops), length);
        var from = relativeIndex(toIntegerOrInfinity(arg(args, 1), ops), length);
        final var end = args.size() < 3 || args.get(2) instanceof JsUndefined
                ? length
                : relativeIndex(toIntegerOrInfinity(args.get(2), ops), length);
        var count = Math.min(end - from, length - to);
        var step = 1L;
        if (from < to && to < from + count) {
            step = -1;
            from = from + count - 1;
            to = to + count - 1;
        }
        while (count > 0) {
            moveOrDelete(target, from, to);
            from += step;
            to += step;
            count--;
        }
        return target.value();
    }

    public static JsValue at(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        final var relative = toIntegerOrInfinity(arg(args, 0), ops);
        final var index = relative >= 0 ? relative : length + relative;
        if (index < 0 || index >= length) {
            return JsUndefined.getInstance();
        }
        return target.get((long) index);
    }

    private ArrayMutation() {
    }
}
