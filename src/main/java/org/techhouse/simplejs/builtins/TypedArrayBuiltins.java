package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.requireNewTarget;
import static org.techhouse.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;
import static org.techhouse.simplejs.builtins.typedarray.ArrayBufferBuiltins.toIndex;
import static org.techhouse.simplejs.builtins.typedarray.DataViewBuiltins.viewNames;
import static org.techhouse.simplejs.builtins.typedarray.Uint8Base64.base64;
import static org.techhouse.simplejs.builtins.typedarray.Uint8Base64.decodeAll;
import static org.techhouse.simplejs.builtins.typedarray.Uint8Base64.hex;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.PropertyTable;

public final class TypedArrayBuiltins {

    public static final List<String> NAMES = List.of("forEach", "map", "filter", "reduce", "reduceRight", "find",
            "findIndex", "some", "every", "indexOf", "lastIndexOf", "includes", "join", "slice", "subarray", "set",
            "fill", "reverse", "at", "toString", "keys", "values", "entries", "sort", "toSorted", "toReversed", "with",
            "findLast", "findLastIndex", "copyWithin", "toLocaleString");
    public static final List<String> UINT8_NAMES = List.of("toBase64", "toHex", "setFromBase64", "setFromHex");
    public static final List<String> BUFFER_NAMES = List.of("slice", "resize", "transfer", "transferToFixedLength");
    public static final List<String> VIEW_NAMES = viewNames();
    public static final List<String> BUFFER_ACCESSOR_NAMES = List.of("byteLength", "maxByteLength", "resizable",
            "detached");
    public static final List<String> VIEW_ACCESSOR_NAMES = List.of("buffer", "byteLength", "byteOffset");
    public static final Set<String> BUFFER_ACCESSORS = Set.copyOf(BUFFER_ACCESSOR_NAMES);
    public static final Set<String> VIEW_ACCESSORS = Set.copyOf(VIEW_ACCESSOR_NAMES);

    private TypedArrayBuiltins() {
    }

    public static JsValue observePrototype(InterpreterOps ops) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (ops == null || newTarget == null || newTarget instanceof JsUndefined) {
            return null;
        }
        return ops.getMember(newTarget, new JsString("prototype"));
    }

    public static JsValue wrapWithObservedPrototype(JsValue constructed, JsValue proto, InterpreterOps ops) {
        if (!(proto instanceof JsObject requested) || proto == ops.getPrototypeOf(constructed)) {
            return constructed;
        }
        final var wrapper = new JsObject();
        wrapper.setPrimitive(constructed);
        wrapper.setProto(requested);
        return wrapper;
    }

    public static JsNativeFunction abstractTypedArray(Invoker invoker, IterableToList iterableToList,
            InterpreterOps ops) {
        final var ctor = new JsNativeFunction("TypedArray", (thisArg, _) -> {
            if (thisArg instanceof JsUndefined) {
                throw new TypeErrorException("Abstract class TypedArray not directly constructable");
            }
            return thisArg;
        });
        installStatics(ctor, invoker, iterableToList, ops);
        return ctor;
    }

    public static void installStatics(JsNativeFunction ctor, Invoker invoker, IterableToList iterableToList,
            InterpreterOps ops) {
        final var from = new JsNativeFunction("from",
                (thisArg, args) -> from(thisArg, args, invoker, iterableToList, ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var of = new JsNativeFunction("of", (thisArg, args) -> of(thisArg, args, ops));
        of.setLength(0);
        ctor.setProperty("of", of);
    }

    public static JsNativeFunction create(JsTypedArray.Kind kind, IterableToList iterableToList, InterpreterOps ops) {
        final var ctor = new JsNativeFunction(kind.ctorName(), (thisArg, args) -> {
            requireNewTarget(kind.ctorName(), thisArg);
            return withNewTargetPrototype(constructTyped(kind, args, iterableToList, ops), ops);
        });
        ctor.setLength(3);
        defineBytesPerElement(ctor.ownProperties(), kind);
        if (kind == JsTypedArray.Kind.UINT8) {
            final var fromBase64 = new JsNativeFunction("fromBase64",
                    (_, args) -> decodeAll(base64(args, ops, Integer.MAX_VALUE), ops));
            fromBase64.setLength(1);
            ctor.setProperty("fromBase64", fromBase64);
            final var fromHex = new JsNativeFunction("fromHex",
                    (_, args) -> decodeAll(hex(args, ops, Integer.MAX_VALUE), ops));
            fromHex.setLength(1);
            ctor.setProperty("fromHex", fromHex);
        }
        return ctor;
    }

    public static void defineBytesPerElement(PropertyTable table, JsTypedArray.Kind kind) {
        table.defineValue("BYTES_PER_ELEMENT", new JsNumber(kind.bytesPerElement()));
        table.setFlags("BYTES_PER_ELEMENT", new JsObject.PropertyFlags(false, false, false));
    }

    public static JsValue constructTyped(JsTypedArray.Kind kind, List<JsValue> args, IterableToList iterableToList,
            InterpreterOps ops) {
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            return allocate(kind, 0, ops);
        }
        final var first = args.getFirst();
        if (first instanceof JsArrayBuffer buffer) {
            return viewOverBuffer(kind, buffer, args, ops);
        }
        if (!InterpreterUtils.isObjectLike(first)) {
            return allocate(kind, toIndex(first, kind.ctorName() + " length", ops), ops);
        }
        return fromItems(kind, sourceItems(first, iterableToList, ops), ops);
    }

    public static JsValue viewOverBuffer(JsTypedArray.Kind kind, JsArrayBuffer buffer, List<JsValue> args,
            InterpreterOps ops) {
        final var bpe = kind.bytesPerElement();
        final var byteOffset = (int) toIndex(arg(args, 1), "typed array offset", ops);
        if (byteOffset % bpe != 0) {
            throw new RangeErrorException("Start offset is not a multiple of the element size");
        }
        final var explicitLength = args.size() > 2 && !(args.get(2) instanceof JsUndefined);
        final var requested = explicitLength ? toIndex(args.get(2), "typed array length", ops) : 0;
        if (buffer.isDetached()) {
            throw new TypeErrorException("Cannot construct a typed array over a detached ArrayBuffer");
        }
        final var bufferLength = buffer.byteLength();
        if (!explicitLength && buffer.isResizable()) {
            if (byteOffset > bufferLength) {
                throw new RangeErrorException("Start offset is outside the bounds of the buffer");
            }
            return new JsTypedArray(kind, buffer, byteOffset, (bufferLength - byteOffset) / bpe, true).withOps(ops);
        }
        if (!explicitLength) {
            if (bufferLength % bpe != 0 || byteOffset > bufferLength) {
                throw new RangeErrorException("Buffer length is not a multiple of the element size");
            }
            return new JsTypedArray(kind, buffer, byteOffset, (bufferLength - byteOffset) / bpe).withOps(ops);
        }
        if (byteOffset + requested * bpe > bufferLength) {
            throw new RangeErrorException("Invalid typed array length");
        }
        return new JsTypedArray(kind, buffer, byteOffset, (int) requested).withOps(ops);
    }

    public static JsTypedArray allocate(JsTypedArray.Kind kind, long length, InterpreterOps ops) {
        final var safe = Math.max(length, 0);
        final var byteLength = safe * kind.bytesPerElement();
        JsArrayBuffer.checkAllocation(byteLength);
        InterpreterOps.charge(ops, byteLength);
        return new JsTypedArray(kind, new JsArrayBuffer((int) byteLength), 0, (int) safe).withOps(ops);
    }

    public static List<JsValue> sourceItems(JsValue source, IterableToList iterableToList, InterpreterOps ops) {
        if (source instanceof JsTypedArray typed) {
            return elements(typed);
        }
        if (ops == null || InterpreterUtils.isCallable(ops.getMember(source, JsSymbol.ITERATOR))) {
            return InterpreterUtils.arrayLikeOrIterableToList(source, iterableToList, ops);
        }
        if (JsCoercion.toNumber(ops.getMember(source, new JsString("length")), ops) > Integer.MAX_VALUE) {
            throw new RangeErrorException("Invalid typed array length");
        }
        return InterpreterUtils.arrayLikeElements(source, ops, false);
    }

    public static JsTypedArray fromItems(JsTypedArray.Kind kind, List<JsValue> items, InterpreterOps ops) {
        final var result = allocate(kind, items.size(), ops);
        for (var i = 0; i < items.size(); i++) {
            result.setElement(i, items.get(i), ops);
        }
        return result;
    }

    public static JsValue from(JsValue constructor, List<JsValue> args, Invoker invoker, IterableToList iterableToList,
            InterpreterOps ops) {
        requireConstructor(constructor);
        final var mapFn = arg(args, 1);
        if (!(mapFn instanceof JsUndefined) && !InterpreterUtils.isCallable(mapFn)) {
            throw new TypeErrorException(JsCoercion.toStr(mapFn) + " is not a function");
        }
        final var items = sourceItems(arg(args, 0), iterableToList, ops);
        final var mapThis = arg(args, 2);
        final var created = typedArrayCreate(constructor, List.of(new JsNumber(items.size())), ops);
        for (var i = 0; i < items.size(); i++) {
            final var element = mapFn instanceof JsUndefined
                    ? items.get(i)
                    : invoker.call(mapFn, mapThis, List.of(items.get(i), new JsNumber(i)));
            writeElement(created, i, element, ops);
        }
        return created;
    }

    public static JsValue of(JsValue constructor, List<JsValue> args, InterpreterOps ops) {
        requireConstructor(constructor);
        final var created = typedArrayCreate(constructor, List.of(new JsNumber(args.size())), ops);
        for (var i = 0; i < args.size(); i++) {
            writeElement(created, i, args.get(i), ops);
        }
        return created;
    }

    public static boolean isTypedArrayConstructor(JsValue constructor) {
        return constructor instanceof JsNativeFunction function && function.getProperty("BYTES_PER_ELEMENT") != null;
    }

    public static void requireConstructor(JsValue constructor) {
        if (!InterpreterUtils.isConstructor(constructor)) {
            throw new TypeErrorException(JsCoercion.toStr(constructor) + " is not a constructor");
        }
    }

    public static void writeElement(JsValue created, int index, JsValue value, InterpreterOps ops) {
        if (created instanceof JsTypedArray typed) {
            typed.setElement(index, value, ops);
            return;
        }
        if (ops == null) {
            asTypedArray(created).setElement(index, value);
            return;
        }
        ops.setMember(created, new JsString(Integer.toString(index)), value);
    }

    public static JsValue typedArrayCreate(JsValue constructor, List<JsValue> ctorArgs, InterpreterOps ops) {
        if (ops == null) {
            throw new TypeErrorException("Cannot construct a typed array without a running interpreter");
        }
        final var created = ops.construct(constructor, ctorArgs);
        final var typed = asTypedArray(created);
        if (typed == null) {
            throw new TypeErrorException("The constructor did not return a typed array");
        }
        validate(typed);
        if (ctorArgs.size() == 1 && typed.length() < JsCoercion.toNumber(ctorArgs.getFirst())) {
            throw new TypeErrorException("The constructor returned a typed array that is too small");
        }
        return created;
    }

    public static List<JsValue> elements(JsTypedArray typed) {
        final var result = new ArrayList<JsValue>();
        for (var i = 0; i < typed.length(); i++) {
            result.add(typed.getElement(i));
        }
        return result;
    }

    public static JsTypedArray validate(JsTypedArray receiver) {
        if (receiver.isOutOfBounds()) {
            throw new TypeErrorException("TypedArray is out of bounds or its buffer has been detached");
        }
        return receiver;
    }

    public static JsTypedArray asTypedArray(JsValue value) {
        if (value instanceof JsTypedArray typed) {
            return typed;
        }
        if (value instanceof JsObject object && object.getPrimitive() instanceof JsTypedArray wrapped) {
            return wrapped;
        }
        return null;
    }

    public static double intArg(List<JsValue> args, int index, double fallback, InterpreterOps ops) {
        if (index >= args.size() || args.get(index) instanceof JsUndefined) {
            return fallback;
        }
        final var value = JsCoercion.toNumber(args.get(index), ops);
        return Double.isNaN(value) ? 0 : value;
    }

    public static boolean boolArg(List<JsValue> args, int index) {
        return index < args.size() && JsCoercion.toBoolean(args.get(index));
    }
}
