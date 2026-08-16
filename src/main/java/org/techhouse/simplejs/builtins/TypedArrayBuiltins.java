package org.techhouse.simplejs.builtins;

import java.math.BigInteger;
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

    public static JsNativeFunction arrayBuffer() {
        final var ctor = new JsNativeFunction("ArrayBuffer", (_, args) -> constructArrayBuffer(args));
        ctor.setProperty("isView", new JsNativeFunction("isView",
                (_, args) -> JsBoolean.of(!args.isEmpty() && args.getFirst() instanceof JsTypedArray)));
        return ctor;
    }

    private static JsValue constructArrayBuffer(List<JsValue> args) {
        final var byteLength = toIndex(arg(args, 0), "ArrayBuffer length");
        JsArrayBuffer.checkAllocation(byteLength);
        if (args.size() > 1 && args.get(1) instanceof JsObject options && options.has("maxByteLength")) {
            final var maxByteLength = toIndex(options.get("maxByteLength"), "ArrayBuffer maxByteLength");
            if (maxByteLength < byteLength) {
                throw new RangeErrorException("ArrayBuffer maxByteLength must be >= byteLength");
            }
            JsArrayBuffer.checkAllocation(maxByteLength);
            return new JsArrayBuffer((int) byteLength, (int) maxByteLength, true);
        }
        return new JsArrayBuffer((int) byteLength);
    }

    public static JsNativeFunction dataView(InterpreterOps ops) {
        return new JsNativeFunction("DataView", (_, args) -> constructDataView(args, ops));
    }

    // The abstract %TypedArray% intrinsic: not directly constructable (mirrors IteratorBuiltins'
    // thisArg-based direct-vs-super-call signal), but is every concrete typed array constructor's
    // own [[Prototype]] (Object.getPrototypeOf(Int8Array) === TypedArray) and owns the shared
    // TypedArray.prototype that every concrete kind's prototype chains up to.
    public static JsNativeFunction abstractTypedArray() {
        return new JsNativeFunction("TypedArray", (thisArg, _) -> {
            if (thisArg instanceof JsUndefined) {
                throw new TypeErrorException("Abstract class TypedArray not directly constructable");
            }
            return thisArg;
        });
    }

    public static JsNativeFunction create(JsTypedArray.Kind kind, Invoker invoker, IterableToList iterableToList,
            InterpreterOps ops) {
        final var ctor = new JsNativeFunction(kind.ctorName(),
                (_, args) -> constructTyped(kind, args, iterableToList, ops));
        defineBytesPerElement(ctor.ownProperties(), kind);
        ctor.setProperty("from",
                new JsNativeFunction("from", (_, args) -> from(kind, args, invoker, iterableToList, ops)));
        ctor.setProperty("of", new JsNativeFunction("of", (_, args) -> fromItems(kind, args)));
        if (kind == JsTypedArray.Kind.UINT8) {
            ctor.setProperty("fromBase64",
                    new JsNativeFunction("fromBase64", (_, args) -> ofBytes(decodeBase64(args))));
            ctor.setProperty("fromHex", new JsNativeFunction("fromHex", (_, args) -> ofBytes(decodeHex(args))));
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
        if (!explicitLength) {
            return new JsDataView(buffer, byteOffset, buffer.byteLength() - byteOffset, buffer.isResizable());
        }
        if (byteOffset + requested > buffer.byteLength()) {
            throw new RangeErrorException("Invalid DataView length");
        }
        return new JsDataView(buffer, byteOffset, requested);
    }

    private static JsValue constructTyped(JsTypedArray.Kind kind, List<JsValue> args, IterableToList iterableToList,
            InterpreterOps ops) {
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            return allocate(kind, 0);
        }
        final var first = args.getFirst();
        if (first instanceof JsArrayBuffer buffer) {
            return viewOverBuffer(kind, buffer, args, ops);
        }
        if (!InterpreterUtils.isObjectLike(first)) {
            return allocate(kind, toIndex(first, kind.ctorName() + " length", ops));
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
            return new JsTypedArray(kind, buffer, byteOffset, (bufferLength - byteOffset) / bpe, true);
        }
        if (!explicitLength) {
            if (bufferLength % bpe != 0 || byteOffset > bufferLength) {
                throw new RangeErrorException("Buffer length is not a multiple of the element size");
            }
            return new JsTypedArray(kind, buffer, byteOffset, (bufferLength - byteOffset) / bpe);
        }
        if (byteOffset + requested * bpe > bufferLength) {
            throw new RangeErrorException("Invalid typed array length");
        }
        return new JsTypedArray(kind, buffer, byteOffset, (int) requested);
    }

    private static JsTypedArray allocate(JsTypedArray.Kind kind, long length) {
        final var safe = Math.max(length, 0);
        final var byteLength = safe * kind.bytesPerElement();
        JsArrayBuffer.checkAllocation(byteLength);
        return new JsTypedArray(kind, new JsArrayBuffer((int) byteLength), 0, (int) safe);
    }

    private static List<JsValue> sourceItems(JsValue source, IterableToList iterableToList, InterpreterOps ops) {
        if (source instanceof JsArray array) {
            return new ArrayList<>(array.getElements());
        }
        if (source instanceof JsTypedArray typed) {
            return elements(typed);
        }
        return InterpreterUtils.arrayLikeOrIterableToList(source, iterableToList, ops);
    }

    private static JsTypedArray fromItems(JsTypedArray.Kind kind, List<JsValue> items) {
        return fromItems(kind, items, null);
    }

    private static JsTypedArray fromItems(JsTypedArray.Kind kind, List<JsValue> items, InterpreterOps ops) {
        final var result = allocate(kind, items.size());
        for (var i = 0; i < items.size(); i++) {
            result.setElement(i, items.get(i), ops);
        }
        return result;
    }

    private static JsValue from(JsTypedArray.Kind kind, List<JsValue> args, Invoker invoker,
            IterableToList iterableToList, InterpreterOps ops) {
        final var source = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var mapFn = args.size() > 1 && !(args.get(1) instanceof JsUndefined) ? args.get(1) : null;
        final var items = sourceItems(source, iterableToList, ops);
        final var result = allocate(kind, items.size());
        for (var i = 0; i < items.size(); i++) {
            final var element = mapFn == null
                    ? items.get(i)
                    : invoker.call(mapFn, JsUndefined.getInstance(), List.of(items.get(i), new JsNumber(i)));
            result.setElement(i, element);
        }
        return result;
    }

    public static JsValue uint8Method(JsTypedArray receiver, String name) {
        return switch (name) {
            case "toBase64" -> new JsNativeFunction("toBase64", (_, args) -> new JsString(toBase64(receiver, args)));
            case "toHex" -> new JsNativeFunction("toHex", (_, _) -> new JsString(toHex(receiver)));
            case "setFromBase64" ->
                new JsNativeFunction("setFromBase64", (_, args) -> setFrom(receiver, decodeBase64(args), 4, 3));
            case "setFromHex" ->
                new JsNativeFunction("setFromHex", (_, args) -> setFrom(receiver, decodeHex(args), 2, 1));
            default -> null;
        };
    }

    private static byte[] bytesOf(JsTypedArray typed) {
        final var bytes = new byte[typed.length()];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (int) JsCoercion.toNumber(typed.getElement(i));
        }
        return bytes;
    }

    private static JsValue ofBytes(byte[] bytes) {
        final var result = new JsTypedArray(JsTypedArray.Kind.UINT8, new JsArrayBuffer(new byte[bytes.length]), 0,
                bytes.length);
        for (var i = 0; i < bytes.length; i++) {
            result.setElement(i, new JsNumber(bytes[i] & 0xFF));
        }
        return result;
    }

    private static String toBase64(JsTypedArray receiver, List<JsValue> args) {
        final var options = args.isEmpty() || !(args.getFirst() instanceof JsObject object) ? null : object;
        var encoder = "base64url".equals(alphabetOf(options)) ? Base64.getUrlEncoder() : Base64.getEncoder();
        if (options != null && JsCoercion.toBoolean(options.get("omitPadding"))) {
            encoder = encoder.withoutPadding();
        }
        return encoder.encodeToString(bytesOf(receiver));
    }

    private static String alphabetOf(JsObject options) {
        if (options == null || options.get("alphabet") instanceof JsUndefined) {
            return "base64";
        }
        final var alphabet = JsCoercion.toStr(options.get("alphabet"));
        if (!"base64".equals(alphabet) && !"base64url".equals(alphabet)) {
            throw new TypeErrorException("Invalid base64 alphabet: " + alphabet);
        }
        return alphabet;
    }

    private static byte[] decodeBase64(List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsString source)) {
            throw new TypeErrorException("Expected a string to decode");
        }
        final var options = args.size() > 1 && args.get(1) instanceof JsObject object ? object : null;
        if (options != null && !(options.get("lastChunkHandling") instanceof JsUndefined)
                && !"loose".equals(JsCoercion.toStr(options.get("lastChunkHandling")))) {
            throw new TypeErrorException("Unsupported lastChunkHandling option");
        }
        final var decoder = "base64url".equals(alphabetOf(options)) ? Base64.getUrlDecoder() : Base64.getDecoder();
        try {
            return decoder.decode(source.getValue());
        } catch (IllegalArgumentException error) {
            throw new SyntaxErrorException("Invalid base64 string");
        }
    }

    private static String toHex(JsTypedArray receiver) {
        final var hex = new StringBuilder();
        for (final var value : bytesOf(receiver)) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    private static byte[] decodeHex(List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsString source)) {
            throw new TypeErrorException("Expected a string to decode");
        }
        final var text = source.getValue();
        if (text.length() % 2 != 0) {
            throw new SyntaxErrorException("Invalid hex string length");
        }
        final var bytes = new byte[text.length() / 2];
        for (var i = 0; i < bytes.length; i++) {
            final var high = Character.digit(text.charAt(i * 2), 16);
            final var low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new SyntaxErrorException("Invalid hex string");
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    private static JsValue setFrom(JsTypedArray receiver, byte[] decoded, int charsPerChunk, int bytesPerChunk) {
        final var written = Math.min(decoded.length, receiver.length());
        for (var i = 0; i < written; i++) {
            receiver.setElement(i, new JsNumber(decoded[i] & 0xFF));
        }
        final var read = written == decoded.length
                ? charsPerChunk * ceilDiv(decoded.length, bytesPerChunk)
                : charsPerChunk * (written / bytesPerChunk);
        final var result = new JsObject();
        result.set("read", new JsNumber(read));
        result.set("written", new JsNumber(written));
        return result;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
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
            case "slice" -> new JsNativeFunction("slice", (_, args) -> buffer.slice((int) intArg(args, 0, 0, ops),
                    (int) intArg(args, 1, buffer.byteLength(), ops)));
            case "resize" -> new JsNativeFunction("resize", (_, args) -> {
                buffer.resize((int) toIndex(arg(args, 0), "ArrayBuffer length", ops));
                return JsUndefined.getInstance();
            });
            case "transfer" ->
                new JsNativeFunction("transfer", (_, args) -> buffer.transfer(transferLength(args, ops), false));
            case "transferToFixedLength" -> new JsNativeFunction("transferToFixedLength",
                    (_, args) -> buffer.transfer(transferLength(args, ops), true));
            default -> null;
        };
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
            return new JsNativeFunction(name, (_, args) -> {
                view.setBigInt(toIndexArg(args, ops), bigArg(args), boolArg(args, 2));
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
            case "keys" -> new JsNativeFunction("keys", (_, _) -> keysIterator(validate(receiver)));
            case "values" ->
                new JsNativeFunction("values", (_, _) -> JsIterators.of(elements(validate(receiver)).iterator()));
            case "entries" -> new JsNativeFunction("entries", (_, _) -> entriesIterator(validate(receiver)));
            case "sort" -> new JsNativeFunction("sort", (_, args) -> sort(receiver, args, invoker));
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

    private static JsValue sort(JsTypedArray receiver, List<JsValue> args, Invoker invoker) {
        final var sorted = sortedElements(receiver, args, invoker);
        for (var i = 0; i < sorted.size(); i++) {
            receiver.setElement(i, sorted.get(i));
        }
        return receiver;
    }

    private static JsValue toSorted(JsTypedArray receiver, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        return fromItems(receiver.kind(), sortedElements(receiver, args, invoker), ops);
    }

    private static List<JsValue> sortedElements(JsTypedArray receiver, List<JsValue> args, Invoker invoker) {
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
                final var result = JsCoercion
                        .toNumber(invoker.call(comparator, JsUndefined.getInstance(), List.of(a, b)));
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
        if (index < 0 || index >= length) {
            throw new RangeErrorException("Invalid index : " + relative);
        }
        final var result = allocate(receiver.kind(), length);
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
        final var provided = args.size() > 1 && !(args.get(1) instanceof JsUndefined);
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

    private static boolean sameNumber(JsValue a, JsValue b) {
        if (a instanceof JsBigInt || b instanceof JsBigInt) {
            return a instanceof JsBigInt x && b instanceof JsBigInt y && x.getValue().equals(y.getValue());
        }
        return JsCoercion.toNumber(a) == JsCoercion.toNumber(b);
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
            return null;
        }
        if (!InterpreterUtils.isCallable(species)) {
            throw new TypeErrorException("The species constructor is not a constructor");
        }
        return species;
    }

    private static JsValue speciesCreate(JsTypedArray exemplar, List<JsValue> ctorArgs, InterpreterOps ops) {
        final var species = speciesConstructor(exemplar, ops);
        if (species == null) {
            return defaultConstruct(exemplar.kind(), ctorArgs);
        }
        final var created = ops.construct(species, ctorArgs);
        final var typed = asTypedArray(created);
        if (typed == null) {
            throw new TypeErrorException("The species constructor did not return a typed array");
        }
        validate(typed);
        if (isBigIntKind(typed.kind()) != isBigIntKind(exemplar.kind())) {
            throw new TypeErrorException("Cannot mix BigInt and other types in a typed array");
        }
        if (ctorArgs.size() == 1 && typed.length() < JsCoercion.toNumber(ctorArgs.getFirst())) {
            throw new TypeErrorException("The species constructor returned a typed array that is too small");
        }
        return created;
    }

    private static JsValue defaultConstruct(JsTypedArray.Kind kind, List<JsValue> ctorArgs) {
        if (ctorArgs.size() == 1) {
            return allocate(kind, (long) JsCoercion.toNumber(ctorArgs.getFirst()));
        }
        final var buffer = (JsArrayBuffer) ctorArgs.getFirst();
        final var byteOffset = (int) JsCoercion.toNumber(ctorArgs.get(1));
        if (ctorArgs.size() < 3 || ctorArgs.get(2) instanceof JsUndefined) {
            final var available = Math.max(buffer.byteLength() - byteOffset, 0);
            return new JsTypedArray(kind, buffer, byteOffset, available / kind.bytesPerElement(), buffer.isResizable());
        }
        return new JsTypedArray(kind, buffer, byteOffset, (int) JsCoercion.toNumber(ctorArgs.get(2)));
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
        if (receiver.isLengthTracking() && !explicitEnd) {
            return new JsTypedArray(receiver.kind(), receiver.getBuffer(), byteOffset, count, true);
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

    // Spec order: the fill value is coerced first, then the range arguments, then the (possibly
    // resized) length is re-read before the write loop.
    private static JsValue fill(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var value = coerceElement(receiver.kind(), arg(args, 0), ops);
        final var length = receiver.length();
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

    private static org.techhouse.simplejs.values.JsObject keysIterator(JsTypedArray receiver) {
        final var keys = new ArrayList<JsValue>();
        for (var i = 0; i < receiver.length(); i++) {
            keys.add(new JsNumber(i));
        }
        return JsIterators.of(keys.iterator());
    }

    private static org.techhouse.simplejs.values.JsObject entriesIterator(JsTypedArray receiver) {
        final var entries = new ArrayList<JsValue>();
        for (var i = 0; i < receiver.length(); i++) {
            entries.add(new JsArray(new ArrayList<>(List.of(new JsNumber(i), receiver.getElement(i)))));
        }
        return JsIterators.of(entries.iterator());
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
        return (int) length;
    }

    // ToIndex: a non-negative integral index bounded by 2^53-1. Unlike intArg's relative/clamping
    // indices this is an absolute position or allocation size, so an out-of-range request is a
    // RangeError rather than an `(int)` cast that silently saturates at Integer.MAX_VALUE - which
    // used to turn an obviously-impossible allocation into a multi-gigabyte attempt.
    private static long toIndex(JsValue value, String label) {
        return toIndex(value, label, null);
    }

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

    private static BigInteger bigArg(List<JsValue> args) {
        return NumberBuiltins.toBigIntValue(arg(args, 1)).getValue();
    }
}
