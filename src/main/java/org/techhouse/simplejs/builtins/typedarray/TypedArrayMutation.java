package org.techhouse.simplejs.builtins.typedarray;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.allocate;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.asTypedArray;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.elements;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.fromItems;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.intArg;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.isTypedArrayConstructor;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.typedArrayCreate;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.validate;
import static org.techhouse.simplejs.builtins.typedarray.TypedArrayIteration.toInteger;
import static org.techhouse.simplejs.values.JsLimits.MAX_SAFE_INTEGER;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Invoker;
import org.techhouse.simplejs.builtins.NumberBuiltins;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class TypedArrayMutation {
    public static JsValue sort(JsTypedArray receiver, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var sorted = sortedElements(receiver, args, invoker, ops);
        for (var i = 0; i < sorted.size(); i++) {
            receiver.setElement(i, sorted.get(i));
        }
        return receiver;
    }

    public static JsValue toSorted(JsTypedArray receiver, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        return fromItems(receiver.kind(), sortedElements(receiver, args, invoker, ops), ops);
    }

    public static List<JsValue> sortedElements(JsTypedArray receiver, List<JsValue> args, Invoker invoker,
            InterpreterOps ops) {
        final var comparator = args.isEmpty() || args.getFirst() instanceof JsUndefined ? null : args.getFirst();
        if (comparator != null && !InterpreterUtils.isCallable(comparator)) {
            throw new TypeErrorException("The comparison function must be either a function or undefined");
        }
        final var items = new ArrayList<>(elements(validate(receiver)));
        if (comparator == null) {
            items.sort(TypedArrayMutation::compareNumeric);
        } else {
            items.sort((a, b) -> {
                final var result = JsCoercion
                        .toNumber(invoker.call(comparator, JsUndefined.getInstance(), List.of(a, b)), ops);
                return Double.isNaN(result) ? 0 : (int) Math.signum(result);
            });
        }
        return items;
    }

    public static int compareNumeric(JsValue a, JsValue b) {
        if (a instanceof JsBigInt x && b instanceof JsBigInt y) {
            return x.getValue().compareTo(y.getValue());
        }
        return Double.compare(JsCoercion.toNumber(a), JsCoercion.toNumber(b));
    }

    public static JsValue toReversed(JsTypedArray receiver, InterpreterOps ops) {
        final var items = new ArrayList<>(elements(receiver));
        java.util.Collections.reverse(items);
        return fromItems(receiver.kind(), items, ops);
    }

    public static JsValue with(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var length = receiver.length();
        final var relative = toInteger(intArg(args, 0, 0, ops));
        final var index = relative < 0 ? length + (int) relative : (int) relative;
        final var replacement = coerceElement(receiver.kind(), arg(args, 1), ops);
        if (!receiver.isValidIntegerIndex(index)) {
            throw new RangeErrorException("Invalid index : " + relative);
        }
        final var result = allocate(receiver.kind(), length, ops);
        for (var i = 0; i < length; i++) {
            result.setElement(i, i == index ? replacement : receiver.getElement(i));
        }
        return result;
    }

    public static JsValue coerceElement(JsTypedArray.Kind kind, JsValue value, InterpreterOps ops) {
        if (kind == JsTypedArray.Kind.BIGINT64 || kind == JsTypedArray.Kind.BIGUINT64) {
            return NumberBuiltins.toBigIntValue(value, ops);
        }
        return new JsNumber(JsCoercion.toNumber(value, ops));
    }

    public static JsValue copyWithin(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var length = receiver.length();
        final var target = resolveIndex(intArg(args, 0, 0, ops), length);
        final var start = resolveIndex(intArg(args, 1, 0, ops), length);
        final var end = resolveIndex(
                args.size() > 2 && !(args.get(2) instanceof JsUndefined) ? intArg(args, 2, 0, ops) : length, length);
        validate(receiver);
        final var slice = new ArrayList<JsValue>();
        for (var i = start; i < end; i++) {
            slice.add(receiver.getElement(i));
        }
        final var live = receiver.length();
        for (var i = 0; i < slice.size() && target + i < live; i++) {
            receiver.setElement(target + i, slice.get(i));
        }
        return receiver;
    }

    public static JsValue fillCreated(JsTypedArray exemplar, List<JsValue> items, InterpreterOps ops) {
        final var created = speciesCreate(exemplar, List.of(new JsNumber(items.size())), ops);
        final var storage = asTypedArray(created);
        for (var i = 0; i < items.size(); i++) {
            storage.setElement(i, items.get(i), ops);
        }
        return created;
    }

    public static JsValue speciesConstructor(JsTypedArray exemplar, InterpreterOps ops) {
        if (ops == null) {
            return null;
        }
        final var constructor = ops.getMember(exemplar, new JsString("constructor"));
        if (constructor instanceof JsUndefined) {
            return null;
        }
        if (!InterpreterUtils.isObjectLike(constructor)) {
            throw new TypeErrorException("The constructor property is not an object");
        }
        final var species = ops.getMember(constructor, JsSymbol.SPECIES);
        if (species instanceof JsUndefined || species instanceof JsNull) {
            return isTypedArrayConstructor(constructor) ? constructor : null;
        }
        if (!InterpreterUtils.isCallable(species)) {
            throw new TypeErrorException("The species constructor is not a constructor");
        }
        return species;
    }

    public static JsValue speciesCreate(JsTypedArray exemplar, List<JsValue> ctorArgs, InterpreterOps ops) {
        final var species = speciesConstructor(exemplar, ops);
        if (species == null) {
            return defaultConstruct(exemplar.kind(), ctorArgs, ops);
        }
        final var created = typedArrayCreate(species, ctorArgs, ops);
        if (isBigIntKind(asTypedArray(created).kind()) != isBigIntKind(exemplar.kind())) {
            throw new TypeErrorException("Cannot mix BigInt and other types in a typed array");
        }
        return created;
    }

    public static JsValue defaultConstruct(JsTypedArray.Kind kind, List<JsValue> ctorArgs, InterpreterOps ops) {
        if (ctorArgs.size() == 1) {
            return allocate(kind, (long) JsCoercion.toNumber(ctorArgs.getFirst()), ops);
        }
        final var buffer = (JsArrayBuffer) ctorArgs.getFirst();
        if (buffer.isDetached()) {
            throw new TypeErrorException("Cannot construct a typed array over a detached ArrayBuffer");
        }
        final var byteOffset = (int) JsCoercion.toNumber(ctorArgs.get(1));
        if (ctorArgs.size() < 3 || ctorArgs.get(2) instanceof JsUndefined) {
            final var available = Math.max(buffer.byteLength() - byteOffset, 0);
            return new JsTypedArray(kind, buffer, byteOffset, available / kind.bytesPerElement(), buffer.isResizable())
                    .withOps(ops);
        }
        return new JsTypedArray(kind, buffer, byteOffset, (int) JsCoercion.toNumber(ctorArgs.get(2))).withOps(ops);
    }

    public static JsValue slice(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var length = receiver.length();
        final var begin = resolveIndex(intArg(args, 0, 0, ops), length);
        final var end = resolveIndex(
                args.size() > 1 && !(args.get(1) instanceof JsUndefined) ? intArg(args, 1, 0, ops) : length, length);
        final var count = Math.max(end - begin, 0);
        final var created = speciesCreate(receiver, List.of(new JsNumber(count)), ops);
        if (count == 0) {
            return created;
        }
        validate(receiver);
        final var storage = asTypedArray(created);
        final var live = Math.min(end, receiver.length());
        for (var i = begin; i < live; i++) {
            storage.setElement(i - begin, receiver.getElement(i), ops);
        }
        return created;
    }

    public static JsValue subarray(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var length = receiver.length();
        final var begin = resolveIndex(intArg(args, 0, 0, ops), length);
        final var explicitEnd = args.size() > 1 && !(args.get(1) instanceof JsUndefined);
        final var end = resolveIndex(explicitEnd ? intArg(args, 1, 0, ops) : length, length);
        final var count = Math.max(end - begin, 0);
        final var byteOffset = receiver.rawByteOffset() + begin * receiver.kind().bytesPerElement();
        if (receiver.isLengthTracking() && !explicitEnd) {
            return speciesCreate(receiver, List.of(receiver.getBuffer(), new JsNumber(byteOffset)), ops);
        }
        return speciesCreate(receiver, List.of(receiver.getBuffer(), new JsNumber(byteOffset), new JsNumber(count)),
                ops);
    }

    public static JsValue set(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var source = arg(args, 0);
        final var offsetValue = toInteger(intArg(args, 1, 0, ops));
        if (offsetValue < 0) {
            throw new RangeErrorException("Start offset must be non-negative");
        }
        validate(receiver);
        final var targetLength = receiver.length();
        if (source instanceof JsTypedArray typed) {
            return setFromTypedArray(receiver, typed, offsetValue, targetLength);
        }
        return setFromArrayLike(receiver, source, offsetValue, targetLength, ops);
    }

    public static JsValue setFromTypedArray(JsTypedArray receiver, JsTypedArray source, double offset,
            int targetLength) {
        validate(source);
        if (isBigIntKind(receiver.kind()) != isBigIntKind(source.kind())) {
            throw new TypeErrorException("Cannot mix BigInt and other types in a typed array set");
        }
        final var items = elements(source);
        if (offset + items.size() > targetLength) {
            throw new RangeErrorException("Source is too large");
        }
        for (var i = 0; i < items.size(); i++) {
            receiver.setElement((int) offset + i, items.get(i));
        }
        return JsUndefined.getInstance();
    }

    public static JsValue setFromArrayLike(JsTypedArray receiver, JsValue source, double offset, int targetLength,
            InterpreterOps ops) {
        if (source instanceof JsUndefined || source instanceof JsNull) {
            throw new TypeErrorException("Cannot convert " + JsCoercion.toStr(source) + " to an object");
        }
        final var sourceLength = arrayLikeLength(source, ops);
        if (Double.isInfinite(offset) || sourceLength + offset > targetLength) {
            throw new RangeErrorException("Source is too large");
        }
        for (var i = 0; i < sourceLength; i++) {
            final var value = ops == null
                    ? JsUndefined.getInstance()
                    : ops.getMember(source, new JsString(Integer.toString(i)));
            receiver.setElement((int) offset + i, value, ops);
        }
        return JsUndefined.getInstance();
    }

    public static int arrayLikeLength(JsValue source, InterpreterOps ops) {
        if (source instanceof JsArray array) {
            return array.getElements().size();
        }
        if (ops == null) {
            return 0;
        }
        final var raw = JsCoercion.toNumber(ops.getMember(source, new JsString("length")), ops);
        if (Double.isNaN(raw) || raw <= 0) {
            return 0;
        }
        return (int) Math.min(raw, MAX_SAFE_INTEGER);
    }

    public static boolean isBigIntKind(JsTypedArray.Kind kind) {
        return kind == JsTypedArray.Kind.BIGINT64 || kind == JsTypedArray.Kind.BIGUINT64;
    }

    public static JsValue fill(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var length = receiver.length();
        final var value = coerceElement(receiver.kind(), arg(args, 0), ops);
        final var start = resolveIndex(intArg(args, 1, 0, ops), length);
        final var end = resolveIndex(
                args.size() > 2 && !(args.get(2) instanceof JsUndefined) ? intArg(args, 2, 0, ops) : length, length);
        validate(receiver);
        final var live = Math.min(end, receiver.length());
        for (var i = start; i < live; i++) {
            receiver.setElement(i, value);
        }
        return receiver;
    }

    public static JsValue reverse(JsTypedArray receiver) {
        final var length = receiver.length();
        for (var i = 0; i < length / 2; i++) {
            final var low = receiver.getElement(i);
            final var high = receiver.getElement(length - 1 - i);
            receiver.setElement(i, high);
            receiver.setElement(length - 1 - i, low);
        }
        return receiver;
    }

    public static JsValue at(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        var index = (int) toInteger(intArg(args, 0, 0, ops));
        if (index < 0) {
            index += receiver.length();
        }
        return receiver.getElement(index);
    }

    public static int resolveIndex(double raw, int length) {
        final var value = (int) raw;
        final var resolved = value < 0 ? length + value : value;
        return Math.clamp(resolved, 0, length);
    }

    private TypedArrayMutation() {
    }
}
