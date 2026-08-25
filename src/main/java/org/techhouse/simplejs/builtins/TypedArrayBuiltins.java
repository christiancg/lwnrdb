package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.PropertyTable;
import org.techhouse.simplejs.values.SameValueZero;

public final class TypedArrayBuiltins {
    public static final List<String> NAMES = List.of("forEach", "map", "filter", "reduce", "reduceRight", "find",
            "findIndex", "some", "every", "indexOf", "lastIndexOf", "includes", "join", "slice", "subarray", "set",
            "fill", "reverse", "at", "toString", "keys", "values", "entries", "sort", "toSorted", "toReversed", "with",
            "findLast", "findLastIndex", "copyWithin", "toLocaleString");
    public static final List<String> UINT8_NAMES = List.of("toBase64", "toHex", "setFromBase64", "setFromHex");
    public static final List<String> BUFFER_NAMES = List.of("slice", "resize", "transfer", "transferToFixedLength");
    public static final List<String> VIEW_NAMES = viewNames();
    private static final List<String> BUFFER_ACCESSOR_NAMES = List.of("byteLength", "maxByteLength", "resizable",
            "detached");
    private static final List<String> VIEW_ACCESSOR_NAMES = List.of("buffer", "byteLength", "byteOffset");
    private static final Set<String> BUFFER_ACCESSORS = Set.copyOf(BUFFER_ACCESSOR_NAMES);
    private static final Set<String> VIEW_ACCESSORS = Set.copyOf(VIEW_ACCESSOR_NAMES);
    private static final double MAX_SAFE_INTEGER = 9007199254740991d;
    private static final String BASE64_DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String BASE64URL_DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final String WHITESPACE = " \t\n\f\r";
    private static final List<String> ALPHABETS = List.of("base64", "base64url");
    private static final List<String> CHUNK_HANDLINGS = List.of("loose", "strict", "stop-before-partial");

    public static List<String> bufferAccessorNames() {
        return BUFFER_ACCESSOR_NAMES;
    }

    public static List<String> viewAccessorNames() {
        return VIEW_ACCESSOR_NAMES;
    }

    private static List<String> viewNames() {
        final var elementTypes = List.of("Int8", "Uint8", "Int16", "Uint16", "Int32", "Uint32", "Float16", "Float32",
                "Float64", "BigInt64", "BigUint64");
        final var names = new ArrayList<String>();
        for (final var type : elementTypes) {
            names.add("get" + type);
            names.add("set" + type);
        }
        return List.copyOf(names);
    }

    public static boolean isBufferAccessor(String name) {
        return BUFFER_ACCESSORS.contains(name);
    }

    public static boolean isViewAccessor(String name) {
        return VIEW_ACCESSORS.contains(name);
    }

    private TypedArrayBuiltins() {
    }

    // Every builtin here is constructor-only: reached without `new` there is no new.target.
    private static void requireNewTarget(String name, JsValue thisArg) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if ((newTarget == null || newTarget instanceof JsUndefined) && thisArg instanceof JsUndefined) {
            throw new TypeErrorException("Constructor " + name + " requires 'new'");
        }
    }

    // GetPrototypeFromConstructor's Get(newTarget, "prototype") is observable, and every one of these
    // constructors runs it only once its arguments have been validated, so a throwing prototype
    // accessor must not pre-empt the TypeError or RangeError. Returns the observed value (or null
    // when there is no new.target to observe) so a caller that still has re-validation left to do
    // can apply it afterwards instead of losing it.
    private static JsValue observePrototype(InterpreterOps ops) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (ops == null || newTarget == null || newTarget instanceof JsUndefined) {
            return null;
        }
        return ops.getMember(newTarget, new JsString("prototype"));
    }

    // OrdinaryCreateFromConstructor: a new.target whose `prototype` is not this kind's intrinsic one
    // (Reflect.construct with a foreign constructor) produces an ordinary object carrying the view
    // as its wrapped primitive, which is how every builtin with internal state is subclassed here.
    private static JsValue withNewTargetPrototype(JsValue constructed, InterpreterOps ops) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (ops == null || newTarget == null || newTarget instanceof JsUndefined) {
            return constructed;
        }
        return wrapWithObservedPrototype(constructed, ops.getMember(newTarget, new JsString("prototype")), ops);
    }

    // Shared tail of OrdinaryCreateFromConstructor once Get(newTarget, "prototype") has already run
    // (some callers, like DataView, must observe it before a later re-validation step rather than
    // right before wrapping, so the Get and the wrap are split into two calls sharing this one).
    private static JsValue wrapWithObservedPrototype(JsValue constructed, JsValue proto, InterpreterOps ops) {
        if (!(proto instanceof JsObject requested) || proto == ops.getPrototypeOf(constructed)) {
            return constructed;
        }
        final var wrapper = new JsObject();
        wrapper.setPrimitive(constructed);
        wrapper.setProto(requested);
        return wrapper;
    }

    public static JsNativeFunction arrayBuffer(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("ArrayBuffer", (thisArg, args) -> {
            requireNewTarget("ArrayBuffer", thisArg);
            return constructArrayBuffer(args, ops);
        });
        ctor.setProperty("isView", new JsNativeFunction("isView", (_, args) -> JsBoolean.of(isView(arg(args, 0)))));
        return ctor;
    }

    // A [[ViewedArrayBuffer]] slot, which a subclass instance carries in its wrapped primitive.
    private static boolean isView(JsValue value) {
        final var target = value instanceof JsObject wrapper ? wrapper.getPrimitive() : value;
        return target instanceof JsTypedArray || target instanceof JsDataView;
    }

    // Spec order for ArrayBuffer(length[, options]) / AllocateArrayBuffer: the byteLength-vs-
    // maxByteLength comparison is a plain numeric check that runs *before* OrdinaryCreateFromConstructor
    // (observing new.target's "prototype", which a getter can make throw), but the actual
    // CreateByteDataBlock allocation - what turns an absurdly large length into a RangeError - runs
    // *after* it. So a maxByteLength violation wins over a throwing prototype getter, while a
    // throwing prototype getter wins over an allocation-size RangeError.
    private static JsValue constructArrayBuffer(List<JsValue> args, InterpreterOps ops) {
        final var byteLength = toIndex(arg(args, 0), "ArrayBuffer length", ops);
        final var options = arg(args, 1);
        final var requestedMax = ops != null && InterpreterUtils.isObjectLike(options)
                ? ops.getMember(options, new JsString("maxByteLength"))
                : JsUndefined.getInstance();
        final var hasMaxByteLength = !(requestedMax instanceof JsUndefined);
        final var maxByteLength = hasMaxByteLength ? toIndex(requestedMax, "ArrayBuffer maxByteLength", ops) : 0;
        if (hasMaxByteLength && maxByteLength < byteLength) {
            throw new RangeErrorException("ArrayBuffer maxByteLength must be >= byteLength");
        }
        final var observedProto = observePrototype(ops);
        JsArrayBuffer.checkAllocation(byteLength);
        InterpreterOps.charge(ops, byteLength);
        final JsValue buffer;
        if (hasMaxByteLength) {
            JsArrayBuffer.checkAllocation(maxByteLength);
            InterpreterOps.charge(ops, maxByteLength);
            buffer = new JsArrayBuffer((int) byteLength, (int) maxByteLength, true);
        } else {
            buffer = new JsArrayBuffer((int) byteLength);
        }
        return wrapWithObservedPrototype(buffer, observedProto, ops);
    }

    public static JsNativeFunction dataView(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("DataView", (thisArg, args) -> {
            requireNewTarget("DataView", thisArg);
            return constructDataView(args, ops);
        });
        ctor.setLength(1);
        return ctor;
    }

    // The abstract %TypedArray% intrinsic: not directly constructable (mirrors IteratorBuiltins'
    // thisArg-based direct-vs-super-call signal), but is every concrete typed array constructor's
    // own [[Prototype]] (Object.getPrototypeOf(Int8Array) === TypedArray) and owns the shared
    // TypedArray.prototype that every concrete kind's prototype chains up to.
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

    // from/of are %TypedArray% statics only; a concrete constructor inherits them through its own
    // [[Prototype]] link to %TypedArray% rather than carrying a copy.
    private static void installStatics(JsNativeFunction ctor, Invoker invoker, IterableToList iterableToList,
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

    // BYTES_PER_ELEMENT lives on both the constructor and its prototype, and is one of the few
    // builtin properties the spec makes entirely immutable.
    public static void defineBytesPerElement(PropertyTable table, JsTypedArray.Kind kind) {
        table.defineValue("BYTES_PER_ELEMENT", new JsNumber(kind.bytesPerElement()));
        table.setFlags("BYTES_PER_ELEMENT", new JsObject.PropertyFlags(false, false, false));
    }

    private static JsValue constructDataView(List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsArrayBuffer buffer)) {
            throw new TypeErrorException("First argument to DataView constructor must be an ArrayBuffer");
        }
        final var byteOffset = (int) toIndex(arg(args, 1), "DataView byteOffset", ops);
        final var explicitLength = args.size() > 2 && !(args.get(2) instanceof JsUndefined);
        final var requested = explicitLength ? (int) toIndex(args.get(2), "DataView byteLength", ops) : 0;
        if (buffer.isDetached()) {
            throw new TypeErrorException("Cannot construct a DataView over a detached ArrayBuffer");
        }
        if (byteOffset > buffer.byteLength()) {
            throw new RangeErrorException("Start offset is outside the bounds of the buffer");
        }
        // OrdinaryCreateFromConstructor sits here in the spec, between the argument checks and the
        // slot assignment, and its Get(newTarget, "prototype") is observable: an accessor that
        // detaches or resizes the buffer must still be caught by the re-check below. The observed
        // value is applied to the constructed view further down, once every RangeError has had its
        // chance to fire first.
        final var observedProto = observePrototype(ops);
        if (buffer.isDetached()) {
            throw new TypeErrorException("Cannot construct a DataView over a detached ArrayBuffer");
        }
        if (byteOffset > buffer.byteLength()) {
            throw new RangeErrorException("Start offset is outside the bounds of the buffer");
        }
        final JsValue view;
        if (!explicitLength) {
            view = new JsDataView(buffer, byteOffset, buffer.byteLength() - byteOffset, buffer.isResizable());
        } else {
            if (byteOffset + requested > buffer.byteLength()) {
                throw new RangeErrorException("Invalid DataView length");
            }
            view = new JsDataView(buffer, byteOffset, requested);
        }
        return wrapWithObservedPrototype(view, observedProto, ops);
    }

    private static JsValue constructTyped(JsTypedArray.Kind kind, List<JsValue> args, IterableToList iterableToList,
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

    // Spec order: both index arguments are coerced first, and only then is the buffer checked for
    // detachment - a valueOf is allowed to detach it and must be observed doing so.
    private static JsValue viewOverBuffer(JsTypedArray.Kind kind, JsArrayBuffer buffer, List<JsValue> args,
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

    private static JsTypedArray allocate(JsTypedArray.Kind kind, long length, InterpreterOps ops) {
        final var safe = Math.max(length, 0);
        final var byteLength = safe * kind.bytesPerElement();
        JsArrayBuffer.checkAllocation(byteLength);
        InterpreterOps.charge(ops, byteLength);
        return new JsTypedArray(kind, new JsArrayBuffer((int) byteLength), 0, (int) safe).withOps(ops);
    }

    private static List<JsValue> sourceItems(JsValue source, IterableToList iterableToList, InterpreterOps ops) {
        // A plain array does NOT get a raw-elements shortcut: per spec (InitializeTypedArrayFromList /
        // the object-argument path) construction from an Array still goes through GetMethod(@@iterator),
        // observably calling whatever `next` the current ArrayIteratorPrototype (or the array's own
        // overridden @@iterator) resolves to, rather than reading the array's own storage directly.
        // A typed array source is different: InitializeTypedArrayFromTypedArray is its own spec
        // algorithm that never consults the iterator protocol, so that shortcut stays.
        if (source instanceof JsTypedArray typed) {
            return elements(typed);
        }
        if (ops == null || InterpreterUtils.isCallable(ops.getMember(source, JsSymbol.ITERATOR))) {
            return InterpreterUtils.arrayLikeOrIterableToList(source, iterableToList, ops);
        }
        // An array-like whose length exceeds what can be allocated is an AllocateTypedArray failure,
        // which the spec reports as a RangeError rather than a bad-receiver TypeError.
        if (JsCoercion.toNumber(ops.getMember(source, new JsString("length")), ops) > Integer.MAX_VALUE) {
            throw new RangeErrorException("Invalid typed array length");
        }
        return InterpreterUtils.arrayLikeElements(source, ops, false);
    }

    private static JsTypedArray fromItems(JsTypedArray.Kind kind, List<JsValue> items, InterpreterOps ops) {
        final var result = allocate(kind, items.size(), ops);
        for (var i = 0; i < items.size(); i++) {
            result.setElement(i, items.get(i), ops);
        }
        return result;
    }

    private static JsValue from(JsValue constructor, List<JsValue> args, Invoker invoker, IterableToList iterableToList,
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

    private static JsValue of(JsValue constructor, List<JsValue> args, InterpreterOps ops) {
        requireConstructor(constructor);
        final var created = typedArrayCreate(constructor, List.of(new JsNumber(args.size())), ops);
        for (var i = 0; i < args.size(); i++) {
            writeElement(created, i, args.get(i), ops);
        }
        return created;
    }

    private static boolean isTypedArrayConstructor(JsValue constructor) {
        return constructor instanceof JsNativeFunction function && function.getProperty("BYTES_PER_ELEMENT") != null;
    }

    private static void requireConstructor(JsValue constructor) {
        if (!InterpreterUtils.isConstructor(constructor)) {
            throw new TypeErrorException(JsCoercion.toStr(constructor) + " is not a constructor");
        }
    }

    // The created object may be a subclass instance wrapping the typed array, so the write goes
    // through [[Set]] on whatever the constructor returned rather than the unwrapped storage.
    private static void writeElement(JsValue created, int index, JsValue value, InterpreterOps ops) {
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

    // TypedArrayCreateFromConstructor: the constructor must hand back a typed array that is in
    // bounds and at least as long as the length it was asked for.
    private static JsValue typedArrayCreate(JsValue constructor, List<JsValue> ctorArgs, InterpreterOps ops) {
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

    public static JsValue uint8Method(JsTypedArray receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "toBase64" ->
                new JsNativeFunction("toBase64", (_, args) -> new JsString(toBase64(receiver, args, ops)));
            case "toHex" -> new JsNativeFunction("toHex", (_, _) -> new JsString(toHex(receiver)));
            case "setFromBase64" -> new JsNativeFunction("setFromBase64",
                    (_, args) -> setFrom(receiver, args, ops, TypedArrayBuiltins::base64));
            case "setFromHex" ->
                new JsNativeFunction("setFromHex", (_, args) -> setFrom(receiver, args, ops, TypedArrayBuiltins::hex));
            default -> null;
        };
    }

    private static byte[] bytesOf(JsTypedArray typed) {
        validate(typed);
        final var bytes = new byte[typed.length()];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (int) JsCoercion.toNumber(typed.getElement(i));
        }
        return bytes;
    }

    private static String toBase64(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var options = arg(args, 0);
        final var alphabet = optionString(options, "alphabet", "base64", ALPHABETS, ops);
        final var omitPadding = ops == null || !InterpreterUtils.isObjectLike(options)
                ? JsBoolean.FALSE
                : ops.getMember(options, new JsString("omitPadding"));
        var encoder = "base64url".equals(alphabet) ? Base64.getUrlEncoder() : Base64.getEncoder();
        if (JsCoercion.toBoolean(omitPadding)) {
            encoder = encoder.withoutPadding();
        }
        return encoder.encodeToString(bytesOf(receiver));
    }

    private static String toHex(JsTypedArray receiver) {
        final var hex = new StringBuilder();
        for (final var value : bytesOf(receiver)) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    // GetOption: the option is read once and, when present, must already be one of the allowed
    // strings - a non-string is rejected without ever being coerced.
    private static String optionString(JsValue options, String key, String fallback, List<String> allowed,
            InterpreterOps ops) {
        if (ops == null || !InterpreterUtils.isObjectLike(options)) {
            if (!(options instanceof JsUndefined) && ops != null) {
                throw new TypeErrorException("Options must be an object");
            }
            return fallback;
        }
        final var value = ops.getMember(options, new JsString(key));
        if (value instanceof JsUndefined) {
            return fallback;
        }
        if (!(value instanceof JsString text) || !allowed.contains(text.getValue())) {
            throw new TypeErrorException("Invalid " + key + " option");
        }
        return text.getValue();
    }

    /** The outcome of a FromBase64/FromHex decode: what was consumed, what was produced, what failed. */
    private record Decoded(int read, byte[] bytes, RuntimeException error) {
    }

    private static String requireSource(List<JsValue> args) {
        if (!(arg(args, 0) instanceof JsString source)) {
            throw new TypeErrorException("Expected a string to decode");
        }
        return source.getValue();
    }

    private interface Decoder {
        Decoded decode(List<JsValue> args, InterpreterOps ops, int maxLength);
    }

    private static Decoded base64(List<JsValue> args, InterpreterOps ops, int maxLength) {
        final var source = requireSource(args);
        final var options = arg(args, 1);
        final var alphabet = optionString(options, "alphabet", "base64", ALPHABETS, ops);
        final var lastChunk = optionString(options, "lastChunkHandling", "loose", CHUNK_HANDLINGS, ops);
        chargeDecode(source, ops);
        return fromBase64(source, alphabet, lastChunk, maxLength);
    }

    private static Decoded hex(List<JsValue> args, InterpreterOps ops, int maxLength) {
        final var source = requireSource(args);
        if (ops != null && !(arg(args, 1) instanceof JsUndefined) && !InterpreterUtils.isObjectLike(arg(args, 1))) {
            throw new TypeErrorException("Options must be an object");
        }
        chargeDecode(source, ops);
        return fromHex(source, maxLength);
    }

    private static JsValue decodeAll(Decoded decoded, InterpreterOps ops) {
        if (decoded.error() != null) {
            throw decoded.error();
        }
        return new JsTypedArray(JsTypedArray.Kind.UINT8, new JsArrayBuffer(decoded.bytes()), 0, decoded.bytes().length)
                .withOps(ops);
    }

    // SetUint8ArrayBytes: whatever was decoded before the failure is written, and only then is the
    // failure reported - a partially valid string leaves the valid prefix in the target.
    private static JsValue setFrom(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops, Decoder decoder) {
        validate(receiver);
        final var decoded = decoder.decode(args, ops, receiver.length());
        // The options bag's `alphabet`/`lastChunkHandling` getters run inside decode() and can detach
        // (or otherwise invalidate) the receiver's buffer; the detached check must be re-run before
        // any byte is written, not just once up front.
        validate(receiver);
        for (var i = 0; i < decoded.bytes().length && i < receiver.length(); i++) {
            receiver.setElement(i, new JsNumber(decoded.bytes()[i] & 0xFF), ops);
        }
        if (decoded.error() != null) {
            throw decoded.error();
        }
        final var result = new JsObject();
        result.set("read", new JsNumber(decoded.read()));
        result.set("written", new JsNumber(decoded.bytes().length));
        return result;
    }

    private static Decoded fromBase64(String source, String alphabet, String lastChunkHandling, int maxLength) {
        if (maxLength == 0) {
            return new Decoded(0, new byte[0], null);
        }
        final var digits = "base64url".equals(alphabet) ? BASE64URL_DIGITS : BASE64_DIGITS;
        final var bytes = new ArrayList<Byte>();
        final var chunk = new StringBuilder();
        var read = 0;
        var index = 0;
        while (true) {
            index = skipWhitespace(source, index);
            if (index == source.length()) {
                return endOfBase64(source, lastChunkHandling, bytes, chunk, read);
            }
            final var character = source.charAt(index);
            index++;
            if (character == '=') {
                return closeBase64(source, lastChunkHandling, digits, bytes, chunk, read, index);
            }
            if (digits.indexOf(character) < 0) {
                return new Decoded(read, toArray(bytes), new SyntaxErrorException("Invalid base64 character"));
            }
            final var remaining = maxLength - bytes.size();
            if (remaining == 1 && chunk.length() == 2 || remaining == 2 && chunk.length() == 3) {
                return new Decoded(read, toArray(bytes), null);
            }
            chunk.append(character);
            if (chunk.length() == 4) {
                decodeChunk(digits, chunk.toString(), false, bytes);
                chunk.setLength(0);
                read = index;
                if (bytes.size() == maxLength) {
                    return new Decoded(read, toArray(bytes), null);
                }
            }
        }
    }

    private static Decoded endOfBase64(String source, String lastChunkHandling, List<Byte> bytes, StringBuilder chunk,
            int read) {
        if (chunk.isEmpty()) {
            return new Decoded(source.length(), toArray(bytes), null);
        }
        if ("stop-before-partial".equals(lastChunkHandling)) {
            return new Decoded(read, toArray(bytes), null);
        }
        if ("strict".equals(lastChunkHandling) || chunk.length() == 1) {
            return new Decoded(read, toArray(bytes), new SyntaxErrorException("Incomplete base64 chunk"));
        }
        decodeChunk(BASE64_DIGITS, chunk.toString(), false, bytes);
        return new Decoded(source.length(), toArray(bytes), null);
    }

    private static Decoded closeBase64(String source, String lastChunkHandling, String digits, List<Byte> bytes,
            StringBuilder chunk, int read, int afterPad) {
        if (chunk.length() < 2) {
            return new Decoded(read, toArray(bytes), new SyntaxErrorException("Unexpected base64 padding"));
        }
        var index = skipWhitespace(source, afterPad);
        if (chunk.length() == 2) {
            if (index == source.length()) {
                return "stop-before-partial".equals(lastChunkHandling)
                        ? new Decoded(read, toArray(bytes), null)
                        : new Decoded(read, toArray(bytes), new SyntaxErrorException("Incomplete base64 padding"));
            }
            if (source.charAt(index) != '=') {
                return new Decoded(read, toArray(bytes), new SyntaxErrorException("Invalid base64 padding"));
            }
            index = skipWhitespace(source, index + 1);
        }
        if (index != source.length()) {
            return new Decoded(read, toArray(bytes), new SyntaxErrorException("Unexpected base64 trailing data"));
        }
        try {
            decodeChunk(digits, chunk.toString(), "strict".equals(lastChunkHandling), bytes);
        } catch (SyntaxErrorException error) {
            return new Decoded(read, toArray(bytes), error);
        }
        return new Decoded(source.length(), toArray(bytes), null);
    }

    // DecodeBase64Chunk: a 2- or 3-character tail carries bits that no output byte holds, and only
    // "strict" rejects a tail whose padding bits are non-zero.
    private static void decodeChunk(String digits, String chunk, boolean throwOnExtraBits, List<Byte> bytes) {
        var accumulated = 0;
        for (var i = 0; i < chunk.length(); i++) {
            accumulated = accumulated << 6 | digits.indexOf(chunk.charAt(i));
        }
        switch (chunk.length()) {
            case 2 -> {
                if (throwOnExtraBits && (accumulated & 0xF) != 0) {
                    throw new SyntaxErrorException("Extra bits in base64 padding");
                }
                bytes.add((byte) (accumulated >> 4));
            }
            case 3 -> {
                if (throwOnExtraBits && (accumulated & 0x3) != 0) {
                    throw new SyntaxErrorException("Extra bits in base64 padding");
                }
                bytes.add((byte) (accumulated >> 10));
                bytes.add((byte) (accumulated >> 2));
            }
            default -> {
                bytes.add((byte) (accumulated >> 16));
                bytes.add((byte) (accumulated >> 8));
                bytes.add((byte) accumulated);
            }
        }
    }

    private static Decoded fromHex(String source, int maxLength) {
        final var bytes = new ArrayList<Byte>();
        // The odd-length rejection precedes the decode loop, so it fires even for a zero-capacity target.
        if (source.length() % 2 != 0) {
            return new Decoded(0, new byte[0], new SyntaxErrorException("Invalid hex string length"));
        }
        var index = 0;
        while (index < source.length() && bytes.size() < maxLength) {
            final var high = Character.digit(source.charAt(index), 16);
            final var low = Character.digit(source.charAt(index + 1), 16);
            if (high < 0 || low < 0 || isNotAsciiHex(source.charAt(index)) || isNotAsciiHex(source.charAt(index + 1))) {
                return new Decoded(index, toArray(bytes), new SyntaxErrorException("Invalid hex string"));
            }
            index += 2;
            bytes.add((byte) (high << 4 | low));
        }
        return new Decoded(index, toArray(bytes), null);
    }

    private static boolean isNotAsciiHex(char character) {
        return (character < '0' || character > '9') && (character < 'a' || character > 'f')
                && (character < 'A' || character > 'F');
    }

    private static int skipWhitespace(String source, int from) {
        var index = from;
        while (index < source.length() && WHITESPACE.indexOf(source.charAt(index)) >= 0) {
            index++;
        }
        return index;
    }

    private static byte[] toArray(List<Byte> bytes) {
        final var result = new byte[bytes.size()];
        for (var i = 0; i < result.length; i++) {
            result[i] = bytes.get(i);
        }
        return result;
    }

    private static void chargeDecode(String source, InterpreterOps ops) {
        InterpreterOps.chargeElements(ops, source.length());
    }

    public static List<JsValue> elements(JsTypedArray typed) {
        final var result = new ArrayList<JsValue>();
        for (var i = 0; i < typed.length(); i++) {
            result.add(typed.getElement(i));
        }
        return result;
    }

    public static JsValue bufferMethod(JsArrayBuffer buffer, String name) {
        return bufferMethod(buffer, name, null);
    }

    public static JsValue bufferMethod(JsArrayBuffer buffer, String name, InterpreterOps ops) {
        return switch (name) {
            case "byteLength" -> new JsNumber(buffer.byteLength());
            case "maxByteLength" -> new JsNumber(buffer.maxByteLength());
            case "resizable" -> JsBoolean.of(buffer.isResizable());
            case "detached" -> JsBoolean.of(buffer.isDetached());
            case "slice" -> new JsNativeFunction("slice", (_, args) -> {
                final var from = (int) intArg(args, 0, 0, ops);
                final var to = (int) intArg(args, 1, buffer.byteLength(), ops);
                InterpreterOps.charge(ops, Math.max(to - from, 0));
                final var sliced = buffer.slice(from, to);
                requireBufferSpecies(buffer, ops);
                return sliced;
            });
            case "resize" -> new JsNativeFunction("resize", (_, args) -> {
                final var resized = (int) toIndex(arg(args, 0), "ArrayBuffer length", ops);
                InterpreterOps.charge(ops, (long) resized + buffer.byteLength());
                buffer.resize(resized);
                return JsUndefined.getInstance();
            });
            case "transfer" -> new JsNativeFunction("transfer", (_, args) -> {
                InterpreterOps.charge(ops, args.isEmpty() ? (long) buffer.byteLength() * 3 : 0);
                return buffer.transfer(transferLength(args, ops), false);
            });
            case "transferToFixedLength" -> new JsNativeFunction("transferToFixedLength", (_, args) -> {
                InterpreterOps.charge(ops, args.isEmpty() ? (long) buffer.byteLength() * 3 : 0);
                return buffer.transfer(transferLength(args, ops), true);
            });
            default -> null;
        };
    }

    // ArrayBuffer.prototype.slice runs SpeciesConstructor, which rejects a non-object, non-undefined
    // `constructor` even though the resulting buffer is not built through it.
    private static void requireBufferSpecies(JsArrayBuffer buffer, InterpreterOps ops) {
        if (ops == null) {
            return;
        }
        final var constructor = ops.getMember(buffer, new JsString("constructor"));
        if (!(constructor instanceof JsUndefined) && !InterpreterUtils.isObjectLike(constructor)) {
            throw new TypeErrorException("The constructor property is not an object");
        }
    }

    public static JsValue dataViewMethod(JsDataView view, String name) {
        return dataViewMethod(view, name, null);
    }

    public static JsValue dataViewMethod(JsDataView view, String name, InterpreterOps ops) {
        return switch (name) {
            case "buffer" -> view.getBuffer();
            case "byteLength" -> new JsNumber(view.byteLength());
            case "byteOffset" -> new JsNumber(view.byteOffset());
            default -> dataViewAccessor(view, name, ops);
        };
    }

    private static JsValue dataViewAccessor(JsDataView view, String name, InterpreterOps ops) {
        if (name.startsWith("getBig")) {
            return new JsNativeFunction(name, (_, args) -> new JsBigInt(
                    view.getBigInt(name.contains("Uint"), toIndexArg(args, ops), boolArg(args, 1))));
        }
        if (name.startsWith("setBig")) {
            // Spec order: the offset is coerced, then the value through ToBigInt, and only then is
            // the range checked - a valueOf on either argument may legally detach the buffer.
            return new JsNativeFunction(name, (_, args) -> {
                final var offset = toIndexArg(args, ops);
                final var value = NumberBuiltins.toBigIntValue(arg(args, 1), ops).getValue();
                view.setBigInt(offset, value, boolArg(args, 2));
                return JsUndefined.getInstance();
            });
        }
        if (name.startsWith("get")) {
            return new JsNativeFunction(name,
                    (_, args) -> new JsNumber(view.getNumber(name, toIndexArg(args, ops), boolArg(args, 1))));
        }
        if (name.startsWith("set")) {
            // Spec order: the offset is coerced first, then the value, and only then is the range
            // checked - a valueOf on either argument can legally detach the buffer in between.
            return new JsNativeFunction(name, (_, args) -> {
                final var offset = toIndexArg(args, ops);
                final var value = JsCoercion.toNumber(arg(args, 1), ops);
                view.setNumber(name, offset, value, boolArg(args, 2));
                return JsUndefined.getInstance();
            });
        }
        return null;
    }

    /**
     * ValidateTypedArray: every {@code %TypedArray%.prototype} method but {@code subarray} rejects a
     * receiver whose buffer has been detached or shrunk out from under its window.
     */
    private static JsTypedArray validate(JsTypedArray receiver) {
        if (receiver.isOutOfBounds()) {
            throw new TypeErrorException("TypedArray is out of bounds or its buffer has been detached");
        }
        return receiver;
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

    private static JsValue sort(JsTypedArray receiver, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var sorted = sortedElements(receiver, args, invoker, ops);
        for (var i = 0; i < sorted.size(); i++) {
            receiver.setElement(i, sorted.get(i));
        }
        return receiver;
    }

    private static JsValue toSorted(JsTypedArray receiver, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        return fromItems(receiver.kind(), sortedElements(receiver, args, invoker, ops), ops);
    }

    private static List<JsValue> sortedElements(JsTypedArray receiver, List<JsValue> args, Invoker invoker,
            InterpreterOps ops) {
        final var comparator = args.isEmpty() || args.getFirst() instanceof JsUndefined ? null : args.getFirst();
        // Spec order: the comparator is rejected before the receiver is validated.
        if (comparator != null && !InterpreterUtils.isCallable(comparator)) {
            throw new TypeErrorException("The comparison function must be either a function or undefined");
        }
        final var items = new ArrayList<>(elements(validate(receiver)));
        if (comparator == null) {
            items.sort(TypedArrayBuiltins::compareNumeric);
        } else {
            items.sort((a, b) -> {
                // The ops-aware overload is required here: a non-primitive comparator result (e.g.
                // one whose only conversion is [Symbol.toPrimitive]) must actually invoke it, not be
                // silently treated as NaN by the data-only overload.
                final var result = JsCoercion
                        .toNumber(invoker.call(comparator, JsUndefined.getInstance(), List.of(a, b)), ops);
                return Double.isNaN(result) ? 0 : (int) Math.signum(result);
            });
        }
        return items;
    }

    private static int compareNumeric(JsValue a, JsValue b) {
        if (a instanceof JsBigInt x && b instanceof JsBigInt y) {
            return x.getValue().compareTo(y.getValue());
        }
        return Double.compare(JsCoercion.toNumber(a), JsCoercion.toNumber(b));
    }

    private static JsValue toReversed(JsTypedArray receiver, InterpreterOps ops) {
        final var items = new ArrayList<>(elements(receiver));
        java.util.Collections.reverse(items);
        return fromItems(receiver.kind(), items, ops);
    }

    // Spec order: the replacement value is coerced before the index is range-checked, so a valueOf
    // that resizes the buffer is observed by the check rather than skipped by it.
    private static JsValue with(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
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

    private static JsValue coerceElement(JsTypedArray.Kind kind, JsValue value, InterpreterOps ops) {
        if (kind == JsTypedArray.Kind.BIGINT64 || kind == JsTypedArray.Kind.BIGUINT64) {
            return NumberBuiltins.toBigIntValue(value, ops);
        }
        return new JsNumber(JsCoercion.toNumber(value, ops));
    }

    private static JsValue findLast(JsTypedArray receiver, List<JsValue> args, Invoker invoker, boolean wantIndex) {
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

    private static JsValue copyWithin(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
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

    // The iteration length is fixed before the first callback runs: a callback that grows the buffer
    // must not extend the walk, and one that shrinks it yields undefined for the vanished indices.
    private static JsValue forEach(JsTypedArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var thisArg = arg(args, 1);
        final var length = receiver.length();
        for (var i = 0; i < length; i++) {
            invoker.call(callback, thisArg, List.of(receiver.getElement(i), new JsNumber(i), receiver));
        }
        return JsUndefined.getInstance();
    }

    private static JsValue map(JsTypedArray receiver, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
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

    private static JsValue filter(JsTypedArray receiver, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
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

    private static JsValue fillCreated(JsTypedArray exemplar, List<JsValue> items, InterpreterOps ops) {
        final var created = speciesCreate(exemplar, List.of(new JsNumber(items.size())), ops);
        final var storage = asTypedArray(created);
        for (var i = 0; i < items.size(); i++) {
            storage.setElement(i, items.get(i), ops);
        }
        return created;
    }

    private static JsValue reduce(JsTypedArray receiver, List<JsValue> args, Invoker invoker, boolean right) {
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

    private static JsValue find(JsTypedArray receiver, List<JsValue> args, Invoker invoker, boolean wantIndex) {
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

    private static boolean some(JsTypedArray receiver, List<JsValue> args, Invoker invoker, boolean every) {
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

    private static int indexOf(JsTypedArray receiver, List<JsValue> args, boolean last, InterpreterOps ops) {
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

    // indexOf scans [from, len-1] ascending; lastIndexOf scans [from, 0] descending, and its default
    // `from` is the last index rather than the first.
    private static int[] searchBounds(List<JsValue> args, int length, boolean last, InterpreterOps ops) {
        // An explicitly passed `undefined` is still a supplied fromIndex, which ToIntegerOrInfinity
        // turns into 0 rather than the omitted-argument default.
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

    private static double toInteger(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    private static boolean includes(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var target = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var length = receiver.length();
        // The length check precedes ToInteger(fromIndex), so a throwing valueOf is never reached.
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

    // indexOf/lastIndexOf compare strictly: a string or object search value never matches an element.
    private static boolean sameNumber(JsValue a, JsValue b) {
        if (a instanceof JsBigInt x) {
            return b instanceof JsBigInt y && x.getValue().equals(y.getValue());
        }
        return a instanceof JsNumber x && b instanceof JsNumber y && x.getValue() == y.getValue();
    }

    private static String join(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        // Spec order: the length is read before the separator is coerced, so a toString that
        // detaches the buffer still leaves the element count it saw.
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

    // SpeciesConstructor: `constructor` is read exactly once, and only a defined, constructible
    // @@species displaces the exemplar's own kind. Returns null for "use the default constructor".
    private static JsValue speciesConstructor(JsTypedArray exemplar, InterpreterOps ops) {
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
            // %TypedArray%[@@species] is an accessor returning `this`, so a typed array constructor
            // that carries no own species is its own species.
            return isTypedArrayConstructor(constructor) ? constructor : null;
        }
        if (!InterpreterUtils.isCallable(species)) {
            throw new TypeErrorException("The species constructor is not a constructor");
        }
        return species;
    }

    private static JsValue speciesCreate(JsTypedArray exemplar, List<JsValue> ctorArgs, InterpreterOps ops) {
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

    private static JsValue defaultConstruct(JsTypedArray.Kind kind, List<JsValue> ctorArgs, InterpreterOps ops) {
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

    public static JsTypedArray asTypedArray(JsValue value) {
        if (value instanceof JsTypedArray typed) {
            return typed;
        }
        if (value instanceof JsObject object && object.getPrimitive() instanceof JsTypedArray wrapped) {
            return wrapped;
        }
        return null;
    }

    private static String toLocaleString(JsTypedArray receiver, Invoker invoker, InterpreterOps ops) {
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

    private static JsValue slice(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
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

    // subarray deliberately does not ValidateTypedArray: a view over a detached or shrunk buffer can
    // still be re-sliced, and the resulting view is simply out of bounds too.
    private static JsValue subarray(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var length = receiver.length();
        final var begin = resolveIndex(intArg(args, 0, 0, ops), length);
        final var explicitEnd = args.size() > 1 && !(args.get(1) instanceof JsUndefined);
        final var end = resolveIndex(explicitEnd ? intArg(args, 1, 0, ops) : length, length);
        final var count = Math.max(end - begin, 0);
        final var byteOffset = receiver.rawByteOffset() + begin * receiver.kind().bytesPerElement();
        // A length-tracking view with no explicit end is re-created without a length, so the new view
        // tracks the buffer too; either way the species constructor is consulted.
        if (receiver.isLengthTracking() && !explicitEnd) {
            return speciesCreate(receiver, List.of(receiver.getBuffer(), new JsNumber(byteOffset)), ops);
        }
        return speciesCreate(receiver, List.of(receiver.getBuffer(), new JsNumber(byteOffset), new JsNumber(count)),
                ops);
    }

    // The offset is coerced and range-checked before the receiver is validated, so a valueOf that
    // detaches the buffer is observed by the validation rather than preceding it.
    private static JsValue set(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
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

    private static JsValue setFromTypedArray(JsTypedArray receiver, JsTypedArray source, double offset,
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

    // The source is read element-by-element through [[Get]], so its getters run in index order and
    // each value is written before the next one is fetched.
    private static JsValue setFromArrayLike(JsTypedArray receiver, JsValue source, double offset, int targetLength,
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

    private static int arrayLikeLength(JsValue source, InterpreterOps ops) {
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

    private static boolean isBigIntKind(JsTypedArray.Kind kind) {
        return kind == JsTypedArray.Kind.BIGINT64 || kind == JsTypedArray.Kind.BIGUINT64;
    }

    // Spec order: the length is captured before the fill value is coerced (so a valueOf that grows
    // the buffer does not extend the range), then the (possibly resized) length is re-read before
    // the write loop.
    private static JsValue fill(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
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

    private static JsValue reverse(JsTypedArray receiver) {
        final var length = receiver.length();
        for (var i = 0; i < length / 2; i++) {
            final var low = receiver.getElement(i);
            final var high = receiver.getElement(length - 1 - i);
            receiver.setElement(i, high);
            receiver.setElement(length - 1 - i, low);
        }
        return receiver;
    }

    private static JsValue at(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        var index = (int) toInteger(intArg(args, 0, 0, ops));
        if (index < 0) {
            index += receiver.length();
        }
        return receiver.getElement(index);
    }

    private interface Step {
        JsValue at(JsTypedArray receiver, int index);
    }

    // A typed array iterator reads the live view one index at a time, so a resize between two next()
    // calls is observed - but once it has reported done it stays done even if the buffer grows back.
    private static JsObject liveIterator(JsTypedArray receiver, Step step) {
        return JsIterators.of(new java.util.Iterator<>() {
            private int index;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                // ValidateTypedArray runs on every step, so a buffer detached between two next() calls
                // is a TypeError rather than a silently shortened walk.
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

    private static int resolveIndex(double raw, int length) {
        final var value = (int) raw;
        final var resolved = value < 0 ? length + value : value;
        return Math.clamp(resolved, 0, length);
    }

    private static JsValue callback(List<JsValue> args) {
        final var fn = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        if (!InterpreterUtils.isCallable(fn)) {
            throw new TypeErrorException(JsCoercion.toStr(fn) + " is not a function");
        }
        return fn;
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }

    private static long toIndexArg(List<JsValue> args, InterpreterOps ops) {
        return toIndex(arg(args, 0), "DataView offset", ops);
    }

    private static int transferLength(List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            return -1;
        }
        final var length = toIndex(args.getFirst(), "ArrayBuffer length", ops);
        JsArrayBuffer.checkAllocation(length);
        InterpreterOps.charge(ops, length * 3);
        return (int) length;
    }

    // ToIndex: a non-negative integral index bounded by 2^53-1. Unlike intArg's relative/clamping
    // indices this is an absolute position or allocation size, so an out-of-range request is a
    // RangeError rather than an `(int)` cast that silently saturates at Integer.MAX_VALUE - which
    // used to turn an obviously-impossible allocation into a multi-gigabyte attempt.
    private static long toIndex(JsValue value, String label, InterpreterOps ops) {
        if (value instanceof JsUndefined) {
            return 0;
        }
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number)) {
            return 0;
        }
        final var integer = number < 0 ? Math.ceil(number) : Math.floor(number);
        if (integer < 0 || integer > MAX_SAFE_INTEGER) {
            throw new RangeErrorException("Invalid " + label + ": " + number);
        }
        return (long) integer;
    }

    private static double intArg(List<JsValue> args, int index, double fallback, InterpreterOps ops) {
        if (index >= args.size() || args.get(index) instanceof JsUndefined) {
            return fallback;
        }
        final var value = JsCoercion.toNumber(args.get(index), ops);
        return Double.isNaN(value) ? 0 : value;
    }

    private static boolean boolArg(List<JsValue> args, int index) {
        return index < args.size() && JsCoercion.toBoolean(args.get(index));
    }

}
