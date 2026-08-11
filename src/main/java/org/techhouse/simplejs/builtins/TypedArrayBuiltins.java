package org.techhouse.simplejs.builtins;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class TypedArrayBuiltins {
    public static final List<String> NAMES = List.of("forEach", "map", "filter", "reduce", "reduceRight", "find",
            "findIndex", "some", "every", "indexOf", "lastIndexOf", "includes", "join", "slice", "subarray", "set",
            "fill", "reverse", "at", "toString", "keys", "values", "entries", "sort", "toSorted", "toReversed", "with",
            "findLast", "findLastIndex", "copyWithin");
    public static final List<String> BUFFER_NAMES = List.of("slice", "resize", "transfer", "transferToFixedLength");
    public static final List<String> VIEW_NAMES = viewNames();
    private static final Set<String> BUFFER_ACCESSORS = Set.of("byteLength", "maxByteLength", "resizable", "detached");
    private static final Set<String> VIEW_ACCESSORS = Set.of("buffer", "byteLength", "byteOffset");

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
        final var byteLength = (int) intArg(args, 0, 0);
        if (args.size() > 1 && args.get(1) instanceof JsObject options && options.has("maxByteLength")) {
            final var maxByteLength = (int) JsCoercion.toNumber(options.get("maxByteLength"));
            if (maxByteLength < byteLength) {
                throw new RangeErrorException("ArrayBuffer maxByteLength must be >= byteLength");
            }
            return new JsArrayBuffer(byteLength, maxByteLength, true);
        }
        return new JsArrayBuffer(byteLength);
    }

    public static JsNativeFunction dataView() {
        return new JsNativeFunction("DataView", (_, args) -> constructDataView(args));
    }

    public static JsNativeFunction create(JsTypedArray.Kind kind, Invoker invoker, IterableToList iterableToList) {
        final var ctor = new JsNativeFunction(kind.ctorName(), (_, args) -> constructTyped(kind, args, iterableToList));
        ctor.setProperty("BYTES_PER_ELEMENT", new JsNumber(kind.bytesPerElement()));
        ctor.setProperty("from", new JsNativeFunction("from", (_, args) -> from(kind, args, invoker, iterableToList)));
        ctor.setProperty("of", new JsNativeFunction("of", (_, args) -> fromItems(kind, args)));
        return ctor;
    }

    private static JsValue constructDataView(List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsArrayBuffer buffer)) {
            throw new TypeErrorException("First argument to DataView constructor must be an ArrayBuffer");
        }
        final var byteOffset = (int) intArg(args, 1, 0);
        final var explicitLength = args.size() > 2 && !(args.get(2) instanceof JsUndefined);
        final var byteLength = explicitLength ? (int) intArg(args, 2, 0) : buffer.byteLength() - byteOffset;
        if (byteOffset < 0 || byteLength < 0 || byteOffset + byteLength > buffer.byteLength()) {
            throw new RangeErrorException("Invalid DataView length");
        }
        return new JsDataView(buffer, byteOffset, byteLength, !explicitLength && buffer.isResizable());
    }

    private static JsValue constructTyped(JsTypedArray.Kind kind, List<JsValue> args, IterableToList iterableToList) {
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            return allocate(kind, 0);
        }
        final var first = args.getFirst();
        if (first instanceof JsArrayBuffer buffer) {
            return viewOverBuffer(kind, buffer, args);
        }
        if (first instanceof JsNumber n) {
            return allocate(kind, (int) n.getValue());
        }
        return fromItems(kind, sourceItems(first, iterableToList));
    }

    private static JsValue viewOverBuffer(JsTypedArray.Kind kind, JsArrayBuffer buffer, List<JsValue> args) {
        final var byteOffset = (int) intArg(args, 1, 0);
        final var bpe = kind.bytesPerElement();
        if (byteOffset < 0 || byteOffset % bpe != 0 || byteOffset > buffer.byteLength()) {
            throw new RangeErrorException("Invalid typed array offset");
        }
        final int length;
        final boolean lengthTracking;
        if (args.size() > 2 && !(args.get(2) instanceof JsUndefined)) {
            length = (int) intArg(args, 2, 0);
            lengthTracking = false;
        } else {
            if ((buffer.byteLength() - byteOffset) % bpe != 0) {
                throw new RangeErrorException("Buffer length is not a multiple of the element size");
            }
            length = (buffer.byteLength() - byteOffset) / bpe;
            lengthTracking = buffer.isResizable();
        }
        if (length < 0 || byteOffset + length * bpe > buffer.byteLength()) {
            throw new RangeErrorException("Invalid typed array length");
        }
        return new JsTypedArray(kind, buffer, byteOffset, length, lengthTracking);
    }

    private static JsTypedArray allocate(JsTypedArray.Kind kind, int length) {
        final var safe = Math.max(length, 0);
        return new JsTypedArray(kind, new JsArrayBuffer(safe * kind.bytesPerElement()), 0, safe);
    }

    private static List<JsValue> sourceItems(JsValue source, IterableToList iterableToList) {
        if (source instanceof JsArray array) {
            return new ArrayList<>(array.getElements());
        }
        if (source instanceof JsTypedArray typed) {
            return elements(typed);
        }
        return iterableToList.drain(source);
    }

    private static JsTypedArray fromItems(JsTypedArray.Kind kind, List<JsValue> items) {
        final var result = allocate(kind, items.size());
        for (var i = 0; i < items.size(); i++) {
            result.setElement(i, items.get(i));
        }
        return result;
    }

    private static JsValue from(JsTypedArray.Kind kind, List<JsValue> args, Invoker invoker,
            IterableToList iterableToList) {
        final var source = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var mapFn = args.size() > 1 && !(args.get(1) instanceof JsUndefined) ? args.get(1) : null;
        final var items = sourceItems(source, iterableToList);
        final var result = allocate(kind, items.size());
        for (var i = 0; i < items.size(); i++) {
            final var element = mapFn == null
                    ? items.get(i)
                    : invoker.call(mapFn, JsUndefined.getInstance(), List.of(items.get(i), new JsNumber(i)));
            result.setElement(i, element);
        }
        return result;
    }

    public static List<JsValue> elements(JsTypedArray typed) {
        final var result = new ArrayList<JsValue>();
        for (var i = 0; i < typed.length(); i++) {
            result.add(typed.getElement(i));
        }
        return result;
    }

    public static JsValue bufferMethod(JsArrayBuffer buffer, String name) {
        return switch (name) {
            case "byteLength" -> new JsNumber(buffer.byteLength());
            case "maxByteLength" -> new JsNumber(buffer.maxByteLength());
            case "resizable" -> JsBoolean.of(buffer.isResizable());
            case "detached" -> JsBoolean.of(buffer.isDetached());
            case "slice" -> new JsNativeFunction("slice",
                    (_, args) -> buffer.slice((int) intArg(args, 0, 0), (int) intArg(args, 1, buffer.byteLength())));
            case "resize" -> new JsNativeFunction("resize", (_, args) -> {
                buffer.resize((int) intArg(args, 0, 0));
                return JsUndefined.getInstance();
            });
            case "transfer" -> new JsNativeFunction("transfer",
                    (_, args) -> buffer.transfer(
                            args.isEmpty() || args.getFirst() instanceof JsUndefined ? -1 : (int) intArg(args, 0, 0),
                            false));
            case "transferToFixedLength" -> new JsNativeFunction("transferToFixedLength",
                    (_, args) -> buffer.transfer(
                            args.isEmpty() || args.getFirst() instanceof JsUndefined ? -1 : (int) intArg(args, 0, 0),
                            true));
            default -> null;
        };
    }

    public static JsValue dataViewMethod(JsDataView view, String name) {
        return switch (name) {
            case "buffer" -> view.getBuffer();
            case "byteLength" -> new JsNumber(view.byteLength());
            case "byteOffset" -> new JsNumber(view.byteOffset());
            default -> dataViewAccessor(view, name);
        };
    }

    private static JsValue dataViewAccessor(JsDataView view, String name) {
        if (name.startsWith("getBig")) {
            return new JsNativeFunction(name, (_, args) -> new JsBigInt(
                    view.getBigInt(name.contains("Uint"), (int) intArg(args, 0, 0), boolArg(args, 1))));
        }
        if (name.startsWith("setBig")) {
            return new JsNativeFunction(name, (_, args) -> {
                view.setBigInt((int) intArg(args, 0, 0), bigArg(args), boolArg(args, 2));
                return JsUndefined.getInstance();
            });
        }
        if (name.startsWith("get")) {
            return new JsNativeFunction(name,
                    (_, args) -> new JsNumber(view.getNumber(name, (int) intArg(args, 0, 0), boolArg(args, 1))));
        }
        if (name.startsWith("set")) {
            return new JsNativeFunction(name, (_, args) -> {
                view.setNumber(name, (int) intArg(args, 0, 0), JsCoercion.toNumber(arg(args, 1)), boolArg(args, 2));
                return JsUndefined.getInstance();
            });
        }
        return null;
    }

    public static JsValue getMethod(JsTypedArray receiver, String name, Invoker invoker) {
        return switch (name) {
            case "forEach" -> new JsNativeFunction("forEach", (_, args) -> forEach(receiver, args, invoker));
            case "map" -> new JsNativeFunction("map", (_, args) -> map(receiver, args, invoker));
            case "filter" -> new JsNativeFunction("filter", (_, args) -> filter(receiver, args, invoker));
            case "reduce" -> new JsNativeFunction("reduce", (_, args) -> reduce(receiver, args, invoker, false));
            case "reduceRight" ->
                new JsNativeFunction("reduceRight", (_, args) -> reduce(receiver, args, invoker, true));
            case "find" -> new JsNativeFunction("find", (_, args) -> find(receiver, args, invoker, false));
            case "findIndex" -> new JsNativeFunction("findIndex", (_, args) -> find(receiver, args, invoker, true));
            case "some" ->
                new JsNativeFunction("some", (_, args) -> JsBoolean.of(some(receiver, args, invoker, false)));
            case "every" ->
                new JsNativeFunction("every", (_, args) -> JsBoolean.of(some(receiver, args, invoker, true)));
            case "indexOf" ->
                new JsNativeFunction("indexOf", (_, args) -> new JsNumber(indexOf(receiver, args, false)));
            case "lastIndexOf" ->
                new JsNativeFunction("lastIndexOf", (_, args) -> new JsNumber(indexOf(receiver, args, true)));
            case "includes" ->
                new JsNativeFunction("includes", (_, args) -> JsBoolean.of(indexOf(receiver, args, false) >= 0));
            case "join" -> new JsNativeFunction("join", (_, args) -> new JsString(join(receiver, args)));
            case "slice" -> new JsNativeFunction("slice", (_, args) -> slice(receiver, args));
            case "subarray" -> new JsNativeFunction("subarray", (_, args) -> subarray(receiver, args));
            case "set" -> new JsNativeFunction("set", (_, args) -> set(receiver, args));
            case "fill" -> new JsNativeFunction("fill", (_, args) -> fill(receiver, args));
            case "reverse" -> new JsNativeFunction("reverse", (_, _) -> reverse(receiver));
            case "at" -> new JsNativeFunction("at", (_, args) -> at(receiver, args));
            case "toString" -> new JsNativeFunction("toString", (_, _) -> new JsString(join(receiver, List.of())));
            case "keys" -> new JsNativeFunction("keys", (_, _) -> keysIterator(receiver));
            case "values" -> new JsNativeFunction("values", (_, _) -> JsIterators.of(elements(receiver).iterator()));
            case "entries" -> new JsNativeFunction("entries", (_, _) -> entriesIterator(receiver));
            case "sort" -> new JsNativeFunction("sort", (_, args) -> sort(receiver, args, invoker));
            case "toSorted" -> new JsNativeFunction("toSorted", (_, args) -> toSorted(receiver, args, invoker));
            case "toReversed" -> new JsNativeFunction("toReversed", (_, _) -> toReversed(receiver));
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, args));
            case "findLast" -> new JsNativeFunction("findLast", (_, args) -> findLast(receiver, args, invoker, false));
            case "findLastIndex" ->
                new JsNativeFunction("findLastIndex", (_, args) -> findLast(receiver, args, invoker, true));
            case "copyWithin" -> new JsNativeFunction("copyWithin", (_, args) -> copyWithin(receiver, args));
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

    private static JsValue toSorted(JsTypedArray receiver, List<JsValue> args, Invoker invoker) {
        return fromItems(receiver.kind(), sortedElements(receiver, args, invoker));
    }

    private static List<JsValue> sortedElements(JsTypedArray receiver, List<JsValue> args, Invoker invoker) {
        final var items = new ArrayList<>(elements(receiver));
        final var comparator = args.isEmpty() || args.getFirst() instanceof JsUndefined ? null : args.getFirst();
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

    private static JsValue toReversed(JsTypedArray receiver) {
        final var items = new ArrayList<>(elements(receiver));
        java.util.Collections.reverse(items);
        return fromItems(receiver.kind(), items);
    }

    private static JsValue with(JsTypedArray receiver, List<JsValue> args) {
        var index = (int) intArg(args, 0, 0);
        if (index < 0) {
            index += receiver.length();
        }
        if (index < 0 || index >= receiver.length()) {
            throw new RangeErrorException("Invalid index : " + intArg(args, 0, 0));
        }
        final var result = allocate(receiver.kind(), receiver.length());
        for (var i = 0; i < receiver.length(); i++) {
            result.setElement(i, i == index ? arg(args, 1) : receiver.getElement(i));
        }
        return result;
    }

    private static JsValue findLast(JsTypedArray receiver, List<JsValue> args, Invoker invoker, boolean wantIndex) {
        final var callback = callback(args);
        for (var i = receiver.length() - 1; i >= 0; i--) {
            final var element = receiver.getElement(i);
            if (JsCoercion.toBoolean(
                    invoker.call(callback, JsUndefined.getInstance(), List.of(element, new JsNumber(i), receiver)))) {
                return wantIndex ? new JsNumber(i) : element;
            }
        }
        return wantIndex ? new JsNumber(-1) : JsUndefined.getInstance();
    }

    private static JsValue copyWithin(JsTypedArray receiver, List<JsValue> args) {
        final var length = receiver.length();
        final var target = resolveIndex(intArg(args, 0, 0), length);
        final var start = resolveIndex(intArg(args, 1, 0), length);
        final var end = resolveIndex(
                args.size() > 2 && !(args.get(2) instanceof JsUndefined) ? intArg(args, 2, 0) : length, length);
        final var slice = new ArrayList<JsValue>();
        for (var i = start; i < end; i++) {
            slice.add(receiver.getElement(i));
        }
        for (var i = 0; i < slice.size() && target + i < length; i++) {
            receiver.setElement(target + i, slice.get(i));
        }
        return receiver;
    }

    private static JsValue forEach(JsTypedArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        for (var i = 0; i < receiver.length(); i++) {
            invoker.call(callback, JsUndefined.getInstance(),
                    List.of(receiver.getElement(i), new JsNumber(i), receiver));
        }
        return JsUndefined.getInstance();
    }

    private static JsValue map(JsTypedArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var result = allocate(receiver.kind(), receiver.length());
        for (var i = 0; i < receiver.length(); i++) {
            result.setElement(i, invoker.call(callback, JsUndefined.getInstance(),
                    List.of(receiver.getElement(i), new JsNumber(i), receiver)));
        }
        return result;
    }

    private static JsValue filter(JsTypedArray receiver, List<JsValue> args, Invoker invoker) {
        final var callback = callback(args);
        final var kept = new ArrayList<JsValue>();
        for (var i = 0; i < receiver.length(); i++) {
            final var element = receiver.getElement(i);
            if (JsCoercion.toBoolean(
                    invoker.call(callback, JsUndefined.getInstance(), List.of(element, new JsNumber(i), receiver)))) {
                kept.add(element);
            }
        }
        return fromItems(receiver.kind(), kept);
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
        for (var i = 0; i < receiver.length(); i++) {
            final var element = receiver.getElement(i);
            if (JsCoercion.toBoolean(
                    invoker.call(callback, JsUndefined.getInstance(), List.of(element, new JsNumber(i), receiver)))) {
                return wantIndex ? new JsNumber(i) : element;
            }
        }
        return wantIndex ? new JsNumber(-1) : JsUndefined.getInstance();
    }

    private static boolean some(JsTypedArray receiver, List<JsValue> args, Invoker invoker, boolean every) {
        final var callback = callback(args);
        for (var i = 0; i < receiver.length(); i++) {
            final var matched = JsCoercion.toBoolean(invoker.call(callback, JsUndefined.getInstance(),
                    List.of(receiver.getElement(i), new JsNumber(i), receiver)));
            if (every && !matched) {
                return false;
            }
            if (!every && matched) {
                return true;
            }
        }
        return every;
    }

    private static int indexOf(JsTypedArray receiver, List<JsValue> args, boolean last) {
        final var target = arg(args, 0);
        final var length = receiver.length();
        for (var step = 0; step < length; step++) {
            final var i = last ? length - 1 - step : step;
            if (sameNumber(receiver.getElement(i), target)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sameNumber(JsValue a, JsValue b) {
        if (a instanceof JsBigInt || b instanceof JsBigInt) {
            return a instanceof JsBigInt x && b instanceof JsBigInt y && x.getValue().equals(y.getValue());
        }
        return JsCoercion.toNumber(a) == JsCoercion.toNumber(b);
    }

    private static String join(JsTypedArray receiver, List<JsValue> args) {
        final var separator = args.isEmpty() || args.getFirst() instanceof JsUndefined
                ? ","
                : JsCoercion.toStr(args.getFirst());
        final var sb = new StringBuilder();
        for (var i = 0; i < receiver.length(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(JsCoercion.toStr(receiver.getElement(i)));
        }
        return sb.toString();
    }

    private static JsValue slice(JsTypedArray receiver, List<JsValue> args) {
        final var begin = resolveIndex(intArg(args, 0, 0), receiver.length());
        final var end = resolveIndex(
                args.size() > 1 && !(args.get(1) instanceof JsUndefined) ? intArg(args, 1, 0) : receiver.length(),
                receiver.length());
        final var items = new ArrayList<JsValue>();
        for (var i = begin; i < end; i++) {
            items.add(receiver.getElement(i));
        }
        return fromItems(receiver.kind(), items);
    }

    private static JsValue subarray(JsTypedArray receiver, List<JsValue> args) {
        final var begin = resolveIndex(intArg(args, 0, 0), receiver.length());
        final var end = resolveIndex(
                args.size() > 1 && !(args.get(1) instanceof JsUndefined) ? intArg(args, 1, 0) : receiver.length(),
                receiver.length());
        final var length = Math.max(end - begin, 0);
        final var byteOffset = receiver.byteOffset() + begin * receiver.kind().bytesPerElement();
        return new JsTypedArray(receiver.kind(), receiver.getBuffer(), byteOffset, length);
    }

    private static JsValue set(JsTypedArray receiver, List<JsValue> args) {
        final var source = arg(args, 0);
        final var offset = (int) intArg(args, 1, 0);
        final List<JsValue> items = switch (source) {
            case JsArray array -> array.getElements();
            case JsTypedArray typed -> elements(typed);
            default -> List.of();
        };
        if (offset + items.size() > receiver.length()) {
            throw new RangeErrorException("Source is too large");
        }
        for (var i = 0; i < items.size(); i++) {
            receiver.setElement(offset + i, items.get(i));
        }
        return JsUndefined.getInstance();
    }

    private static JsValue fill(JsTypedArray receiver, List<JsValue> args) {
        final var value = arg(args, 0);
        final var start = resolveIndex(intArg(args, 1, 0), receiver.length());
        final var end = resolveIndex(
                args.size() > 2 && !(args.get(2) instanceof JsUndefined) ? intArg(args, 2, 0) : receiver.length(),
                receiver.length());
        for (var i = start; i < end; i++) {
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

    private static JsValue at(JsTypedArray receiver, List<JsValue> args) {
        var index = (int) intArg(args, 0, 0);
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
        if (args.isEmpty()) {
            throw new TypeErrorException("undefined is not a function");
        }
        return args.getFirst();
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }

    private static double intArg(List<JsValue> args, int index, double fallback) {
        if (index >= args.size() || args.get(index) instanceof JsUndefined) {
            return fallback;
        }
        final var value = JsCoercion.toNumber(args.get(index));
        return Double.isNaN(value) ? 0 : value;
    }

    private static boolean boolArg(List<JsValue> args, int index) {
        return index < args.size() && JsCoercion.toBoolean(args.get(index));
    }

    private static BigInteger bigArg(List<JsValue> args) {
        final var value = arg(args, 1);
        if (!(value instanceof JsBigInt b)) {
            throw new TypeErrorException("Cannot convert " + JsCoercion.toStr(value) + " to a BigInt");
        }
        return b.getValue();
    }
}
