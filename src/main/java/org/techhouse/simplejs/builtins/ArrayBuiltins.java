package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.PropertyDescriptor;
import org.techhouse.simplejs.values.SameValueZero;

public final class ArrayBuiltins {
    public static final List<String> NAMES = List.of("toLocaleString", "map", "filter", "reduce", "forEach", "find",
            "some", "every", "includes", "indexOf", "slice", "splice", "concat", "join", "toString", "push", "pop",
            "shift", "unshift", "sort", "flat", "findIndex", "findLast", "findLastIndex", "lastIndexOf", "reduceRight",
            "flatMap", "fill", "copyWithin", "reverse", "at", "keys", "values", "entries", "toReversed", "toSorted",
            "toSpliced", "with");

    // 2^53-1: the largest index the spec's ToLength admits, so every index walk is done in `long`.
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private static final JsString LENGTH = new JsString("length");
    private static final JsString NEXT = new JsString("next");
    private static final JsString DONE = new JsString("done");
    private static final JsString VALUE = new JsString("value");
    private static final JsString RETURN = new JsString("return");
    private static final JsString TO_STRING = new JsString("toString");

    private ArrayBuiltins() {
    }

    public static JsNativeFunction create(Invoker invoker, EventLoop eventLoop,
                                          InterpreterOps ops, Intrinsics intrinsics) {
        final var array = new JsNativeFunction("Array", (_, args) -> construct(args));
        array.setProperty("isArray",
                new JsNativeFunction("isArray", (_, args) -> JsBoolean.of(isArray(arg(args, 0), intrinsics))));
        array.setProperty("from", new JsNativeFunction("from", (receiver, args) -> from(receiver, args, invoker, ops)));
        array.setProperty("of", new JsNativeFunction("of", (receiver, args) -> of(receiver, args, ops)));
        final var fromAsync = new JsNativeFunction("fromAsync",
                (receiver, args) -> AsyncIteratorBuiltins.fromAsync(ops, eventLoop, receiver, args));
        fromAsync.setLength(1);
        array.setProperty("fromAsync", fromAsync);
        return array;
    }

    // IsArray: a proxy answers for its target (and a revoked one is a TypeError), and the intrinsic
    // Array.prototype is itself an array exotic object even though it is stored as a plain object.
    static boolean isArray(JsValue value) {
        if (value instanceof JsProxy proxy) {
            if (proxy.isRevoked()) {
                throw new TypeErrorException("Cannot perform 'IsArray' on a proxy that has been revoked");
            }
            return isArray(proxy.getTarget());
        }
        return value instanceof JsArray;
    }

    private static boolean isArray(JsValue value, Intrinsics intrinsics) {
        return isArray(value) || (intrinsics != null && value == intrinsics.arrayProto());
    }

    private static JsValue of(JsValue receiver, List<JsValue> args, InterpreterOps ops) {
        final var length = args.size();
        final var result = InterpreterUtils.isConstructor(receiver)
                ? ops.construct(receiver, List.of(new JsNumber(length)))
                : newArray(length);
        for (var i = 0; i < length; i++) {
            createDataPropertyOrThrow(result, i, args.get(i), ops);
        }
        setLengthOrThrow(result, length, ops);
        return result;
    }

    private static JsValue from(JsValue receiver, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var source = arg(args, 0);
        final var mapFn = arg(args, 1);
        if (!(mapFn instanceof JsUndefined) && !InterpreterUtils.isCallable(mapFn)) {
            throw new TypeErrorException("Array.from: when provided, the second argument must be a function");
        }
        final var mapThisArg = arg(args, 2);
        final var iteratorMethod = ops == null ? null : ops.getMember(source, JsSymbol.ITERATOR);
        if (InterpreterUtils.isCallable(iteratorMethod)) {
            return fromIterator(receiver, source, iteratorMethod, mapFn, mapThisArg, invoker, ops);
        }
        return fromArrayLike(receiver, source, mapFn, mapThisArg, invoker, ops);
    }

    // The iterator path constructs the target with no arguments; only the array-like path passes the
    // length. `new Object(0)` boxes its argument, so the two are not interchangeable.
    private static JsValue fromIterator(JsValue receiver, JsValue source, JsValue iteratorMethod, JsValue mapFn,
            JsValue mapThisArg, Invoker invoker, InterpreterOps ops) {
        final var result = InterpreterUtils.isConstructor(receiver)
                ? ops.construct(receiver, List.of())
                : new JsArray();
        final var iterator = ops.call(iteratorMethod, source, List.of());
        final var next = ops.getMember(iterator, NEXT);
        var written = 0L;
        while (true) {
            final var step = ops.call(next, iterator, List.of());
            if (JsCoercion.toBoolean(ops.getMember(step, DONE))) {
                setLengthOrThrow(result, written, ops);
                return result;
            }
            var element = ops.getMember(step, VALUE);
            try {
                if (!(mapFn instanceof JsUndefined)) {
                    element = invoker.call(mapFn, mapThisArg, List.of(element, new JsNumber(written)));
                }
                createDataPropertyOrThrow(result, written, element, ops);
            } catch (RuntimeException error) {
                closeIterator(iterator, ops, error);
                throw error;
            }
            written++;
        }
    }

    private static JsValue fromArrayLike(JsValue receiver, JsValue source, JsValue mapFn, JsValue mapThisArg,
            Invoker invoker, InterpreterOps ops) {
        final var arrayLike = new ArrayLike(requireObjectSource(source), ops);
        final var length = arrayLike.length();
        final var result = InterpreterUtils.isConstructor(receiver)
                ? ops.construct(receiver, List.of(new JsNumber(length)))
                : newArray(length);
        for (var i = 0L; i < length; i++) {
            final var element = arrayLike.get(i);
            final var mapped = mapFn instanceof JsUndefined
                    ? element
                    : invoker.call(mapFn, mapThisArg, List.of(element, new JsNumber(i)));
            createDataPropertyOrThrow(result, i, mapped, ops);
        }
        setLengthOrThrow(result, length, ops);
        return result;
    }

    private static JsValue requireObjectSource(JsValue source) {
        if (source instanceof JsNull || source instanceof JsUndefined) {
            throw new TypeErrorException("Array.from requires an array-like or iterable object");
        }
        return source;
    }

    // IteratorClose: the pending error wins, so a throw from the iterator's own `return` is dropped.
    private static void closeIterator(JsValue iterator, InterpreterOps ops, RuntimeException pending) {
        if (pending instanceof ScriptAbortException) {
            return;
        }
        try {
            final var close = ops.getMember(iterator, RETURN);
            if (InterpreterUtils.isCallable(close)) {
                ops.call(close, iterator, List.of());
            }
        } catch (RuntimeException ignored) {
            // IteratorClose swallows a failure from `return` when an error is already propagating.
        }
    }

    private static void setLengthOrThrow(JsValue target, long length, InterpreterOps ops) {
        if (target instanceof JsArray array) {
            if (!array.setLength((int) length)) {
                throw new TypeErrorException("Cannot assign to read only property 'length' of object");
            }
            return;
        }
        if (ops != null && !ops.setMember(target, LENGTH, new JsNumber(length))) {
            throw new TypeErrorException("Cannot assign to read only property 'length' of object");
        }
    }

    private static void createDataPropertyOrThrow(JsValue target, long index, JsValue value, InterpreterOps ops) {
        if (target instanceof JsArray array) {
            if (index > Integer.MAX_VALUE || !array.set((int) index, value)) {
                throw new TypeErrorException("Cannot define property " + index + ", object is not extensible");
            }
            return;
        }
        final var descriptor = new JsObject();
        descriptor.set("value", value);
        descriptor.set("writable", JsBoolean.of(true));
        descriptor.set("enumerable", JsBoolean.of(true));
        descriptor.set("configurable", JsBoolean.of(true));
        if (ops == null || !ops.defineProperty(target, new JsString(Long.toString(index)), descriptor)) {
            throw new TypeErrorException("Cannot define property " + index + ", object is not extensible");
        }
    }

    // Array(len) allocates holes, not undefined elements: a callback method must not visit them.
    private static JsValue construct(List<JsValue> args) {
        if (args.size() == 1 && args.getFirst() instanceof JsNumber number) {
            final var length = number.getValue();
            if (length < 0 || length != Math.floor(length) || length > 4294967295D) {
                throw new RangeErrorException("Invalid array length");
            }
            return newArray((long) length);
        }
        return new JsArray(new ArrayList<>(args));
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

    private static ArrayLike target(JsValue receiver, InterpreterOps ops) {
        return new ArrayLike(receiver, ops);
    }

    // The spec's array-like receiver: every index is read, written and deleted lazily through the
    // member seam, so a getter/setter, a proxy trap or a frozen slot behaves exactly as it would on
    // the receiver itself. A plain dense JsArray without index accessors short-circuits to its
    // backing list, which is observationally identical and avoids a key allocation per element.
    private static final class ArrayLike {
        private final JsValue value;
        private final InterpreterOps ops;

        ArrayLike(JsValue value, InterpreterOps ops) {
            this.value = value;
            this.ops = ops;
        }

        private JsArray dense() {
            return value instanceof JsArray array && !array.hasAnyIndexAccessor() ? array : null;
        }

        long length() {
            return toLength(getKey(LENGTH), ops);
        }

        JsValue getKey(JsValue key) {
            return ops == null ? JsUndefined.getInstance() : ops.getMember(value, key);
        }

        JsValue get(long index) {
            final var dense = dense();
            if (dense != null && index >= 0 && index < dense.length() && !dense.isHole((int) index)) {
                return dense.get((int) index);
            }
            final var element = getKey(key(index));
            return element == JsUndefined.getHole() ? JsUndefined.getInstance() : element;
        }

        boolean has(long index) {
            final var dense = dense();
            if (dense != null && index >= 0 && index < dense.length() && !dense.isHole((int) index)) {
                return true;
            }
            if (ops == null) {
                return false;
            }
            final var key = key(index);
            if (supportsHas(value) && ops.has(value, key)) {
                return true;
            }
            return needsReadFallback(value) && !(ops.getMember(value, key) instanceof JsUndefined);
        }

        // A value type the `in` operator rejects outright, and a primitive wrapper (whose indexed
        // properties live on the wrapped primitive rather than on the object), are both answered by
        // a plain read instead.
        private static boolean needsReadFallback(JsValue value) {
            return !supportsHas(value) || (value instanceof JsObject object && object.getPrimitive() != null);
        }

        void set(long index, JsValue element) {
            if (!trySet(index, element)) {
                throw new TypeErrorException("Cannot assign to read only property '" + index + "' of the receiver");
            }
        }

        private boolean trySet(long index, JsValue element) {
            final var inherited = inheritedIndexAccessor(index);
            if (inherited != null) {
                final var setter = inherited.get("set");
                if (!InterpreterUtils.isCallable(setter)) {
                    return false;
                }
                ops.call(setter, value, List.of(element));
                return true;
            }
            final var dense = dense();
            if (dense != null) {
                return index <= Integer.MAX_VALUE && dense.set((int) index, element);
            }
            if (ops == null) {
                return true;
            }
            // A string's ToObject wrapper has non-writable index and length properties, so an
            // in-range write is rejected rather than silently dropped.
            if (wrappedString() instanceof JsString string) {
                return index >= string.getValue().length();
            }
            return ops.setMember(value, key(index), element);
        }

        // OrdinarySet on an index the array does not own must reach an accessor the prototype chain
        // owns; the interpreter's array write path only ever consults the array itself.
        private JsObject inheritedIndexAccessor(long index) {
            if (ops == null || !(value instanceof JsArray array)) {
                return null;
            }
            final var propertyKey = key(index);
            if (!ops.has(value, propertyKey) || array.getOwnProperty(propertyKey) != null) {
                return null;
            }
            return inheritedAccessor(propertyKey);
        }

        private JsObject inheritedAccessor(JsString propertyKey) {
            JsObject accessor = null;
            var proto = ops.getPrototypeOf(value);
            while (accessor == null && InterpreterUtils.isObjectLike(proto)) {
                final var descriptor = ops.getOwnPropertyDescriptor(proto, propertyKey);
                if (descriptor instanceof JsObject fields) {
                    // A data property found first on the chain is what an ordinary write overwrites,
                    // so the walk stops without an accessor.
                    accessor = fields.has("get") || fields.has("set") ? fields : null;
                    proto = JsNull.getInstance();
                } else {
                    proto = ops.getPrototypeOf(proto);
                }
            }
            return accessor;
        }

        void setLength(long length) {
            if (wrappedString() != null || (ops != null && !ops.setMember(value, LENGTH, new JsNumber(length)))) {
                throw new TypeErrorException("Cannot assign to read only property 'length' of the receiver");
            }
        }

        private JsValue wrappedString() {
            if (value instanceof JsString) {
                return value;
            }
            return value instanceof JsObject object && object.getPrimitive() instanceof JsString
                    ? object.getPrimitive()
                    : null;
        }

        void delete(long index) {
            if (ops == null) {
                final var dense = dense();
                if (dense != null && index >= 0 && index < dense.length()) {
                    dense.clearIndexToHole((int) index);
                }
                return;
            }
            if (!ops.deleteMember(value, key(index))) {
                throw new TypeErrorException("Cannot delete property '" + index + "' of the receiver");
            }
        }

        // HasProperty is only defined over the value types the interpreter's `in` operator accepts;
        // everything else (a string, an arguments object, a typed array, a Map) answers through a
        // plain read, which is exact for them because none of them stores an own undefined.
        private static boolean supportsHas(JsValue value) {
            return value instanceof JsObject || value instanceof JsProxy || value instanceof JsArray
                    || value instanceof JsClass || value instanceof JsFunction || value instanceof JsNativeFunction
                    || value instanceof JsArguments || value instanceof JsTypedArray;
        }
    }

    private static JsString key(long index) {
        return new JsString(Long.toString(index));
    }

    private static long toLength(JsValue value, InterpreterOps ops) {
        final var number = toIntegerOrInfinity(value, ops);
        if (number <= 0) {
            return 0;
        }
        return (long) Math.min(number, (double) MAX_SAFE_INTEGER);
    }

    private static double toIntegerOrInfinity(JsValue value, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number)) {
            return 0;
        }
        if (Double.isInfinite(number)) {
            return number;
        }
        return number < 0 ? Math.ceil(number) : Math.floor(number);
    }

    private static long relativeIndex(double relative, long length) {
        if (relative < 0) {
            return relative == Double.NEGATIVE_INFINITY ? 0 : (long) Math.max(length + relative, 0);
        }
        return (long) Math.min(relative, (double) length);
    }

    private static JsValue arg(List<JsValue> args, int position) {
        return position < args.size() ? args.get(position) : JsUndefined.getInstance();
    }

    private static JsArray newArray(long length) {
        if (length > Integer.MAX_VALUE) {
            throw new RangeErrorException("Invalid array length");
        }
        final var result = new JsArray();
        result.setLength((int) length);
        return result;
    }

    // ArraySpeciesCreate: only an array receiver consults its constructor, and the intrinsic Array
    // carries no @@species, so the common case still lands on a plain array.
    private static JsValue speciesCreate(ArrayLike target, long length, InterpreterOps ops) {
        if (ops == null || !isArray(target.value)) {
            return newArray(length);
        }
        var constructor = ops.getMember(target.value, new JsString("constructor"));
        if (InterpreterUtils.isObjectLike(constructor)) {
            constructor = ops.getMember(constructor, JsSymbol.SPECIES);
            if (constructor instanceof JsNull) {
                constructor = JsUndefined.getInstance();
            }
        }
        if (constructor instanceof JsUndefined) {
            return newArray(length);
        }
        if (!InterpreterUtils.isConstructor(constructor)) {
            throw new TypeErrorException("The constructor property is not a constructor");
        }
        return ops.construct(constructor, List.of(new JsNumber(length)));
    }

    private static void setResultLength(JsValue result, long length, InterpreterOps ops) {
        if (result instanceof JsArray array) {
            array.setLength((int) length);
        } else if (ops != null) {
            ops.setMember(result, LENGTH, new JsNumber(length));
        }
    }

    private static String toLocaleString(ArrayLike target, Invoker invoker, InterpreterOps ops) {
        final var length = target.length();
        final var sb = new StringBuilder();
        for (var i = 0L; i < length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            final var element = target.get(i);
            if (element instanceof JsNull || element instanceof JsUndefined) {
                continue;
            }
            final var method = ops == null
                    ? JsUndefined.getInstance()
                    : ops.getMember(element, new JsString("toLocaleString"));
            if (InterpreterUtils.isCallable(method)) {
                sb.append(JsCoercion.toStr(invoker.call(method, element, List.of()), ops));
                continue;
            }
            // Object.prototype.toLocaleString is `this.toString()`, so an element that inherits no
            // toLocaleString of its own still has to route through whatever toString it resolves to.
            final var toString = ops == null ? JsUndefined.getInstance() : ops.getMember(element, TO_STRING);
            if (InterpreterUtils.isCallable(toString)) {
                sb.append(JsCoercion.toStr(invoker.call(toString, element, List.of()), ops));
            } else {
                sb.append(JsCoercion.toStr(element, ops));
            }
        }
        return sb.toString();
    }

    private static JsValue map(ArrayLike target, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        final var result = speciesCreate(target, length, ops);
        for (var i = 0L; i < length; i++) {
            if (target.has(i)) {
                final var mapped = invoker.call(callback, self, List.of(target.get(i), new JsNumber(i), target.value));
                createDataPropertyOrThrow(result, i, mapped, ops);
            }
        }
        return result;
    }

    private static JsValue filter(ArrayLike target, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        final var result = speciesCreate(target, 0, ops);
        var kept = 0L;
        for (var i = 0L; i < length; i++) {
            if (!target.has(i)) {
                continue;
            }
            final var element = target.get(i);
            if (JsCoercion.toBoolean(invoker.call(callback, self, List.of(element, new JsNumber(i), target.value)))) {
                createDataPropertyOrThrow(result, kept, element, ops);
                kept++;
            }
        }
        return result;
    }

    private static JsValue reduce(ArrayLike target, List<JsValue> args, Invoker invoker) {
        final var length = target.length();
        final var callback = callback(args);
        var index = 0L;
        JsValue accumulator = null;
        if (args.size() >= 2) {
            accumulator = args.get(1);
        } else {
            while (accumulator == null && index < length) {
                if (target.has(index)) {
                    accumulator = target.get(index);
                }
                index++;
            }
            if (accumulator == null) {
                throw new TypeErrorException("Reduce of empty array with no initial value");
            }
        }
        for (var i = index; i < length; i++) {
            if (target.has(i)) {
                accumulator = invoker.call(callback, JsUndefined.getInstance(),
                        List.of(accumulator, target.get(i), new JsNumber(i), target.value));
            }
        }
        return accumulator;
    }

    private static JsValue reduceRight(ArrayLike target, List<JsValue> args, Invoker invoker) {
        var index = target.length() - 1;
        final var callback = callback(args);
        JsValue accumulator = null;
        if (args.size() >= 2) {
            accumulator = args.get(1);
        } else {
            while (accumulator == null && index >= 0) {
                if (target.has(index)) {
                    accumulator = target.get(index);
                }
                index--;
            }
            if (accumulator == null) {
                throw new TypeErrorException("Reduce of empty array with no initial value");
            }
        }
        for (var i = index; i >= 0; i--) {
            if (target.has(i)) {
                accumulator = invoker.call(callback, JsUndefined.getInstance(),
                        List.of(accumulator, target.get(i), new JsNumber(i), target.value));
            }
        }
        return accumulator;
    }

    private static JsValue forEach(ArrayLike target, List<JsValue> args, Invoker invoker) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        for (var i = 0L; i < length; i++) {
            if (target.has(i)) {
                invoker.call(callback, self, List.of(target.get(i), new JsNumber(i), target.value));
            }
        }
        return JsUndefined.getInstance();
    }

    private static JsValue find(ArrayLike target, List<JsValue> args, Invoker invoker, boolean forwards) {
        final var found = findMatch(target, args, invoker, forwards);
        return found == null ? JsUndefined.getInstance() : found.value;
    }

    private static long findIndex(ArrayLike target, List<JsValue> args, Invoker invoker, boolean forwards) {
        final var found = findMatch(target, args, invoker, forwards);
        return found == null ? -1 : found.index;
    }

    private record Match(long index, JsValue value) {
    }

    // find/findIndex/findLast/findLastIndex read every index unconditionally: unlike the callback
    // family, an absent index is visited with undefined rather than skipped.
    private static Match findMatch(ArrayLike target, List<JsValue> args, Invoker invoker, boolean forwards) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        for (var step = 0L; step < length; step++) {
            final var i = forwards ? step : length - 1 - step;
            final var element = target.get(i);
            if (JsCoercion.toBoolean(invoker.call(callback, self, List.of(element, new JsNumber(i), target.value)))) {
                return new Match(i, element);
            }
        }
        return null;
    }

    private static boolean some(ArrayLike target, List<JsValue> args, Invoker invoker) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        for (var i = 0L; i < length; i++) {
            if (target.has(i) && JsCoercion
                    .toBoolean(invoker.call(callback, self, List.of(target.get(i), new JsNumber(i), target.value)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean every(ArrayLike target, List<JsValue> args, Invoker invoker) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        for (var i = 0L; i < length; i++) {
            if (target.has(i) && !JsCoercion
                    .toBoolean(invoker.call(callback, self, List.of(target.get(i), new JsNumber(i), target.value)))) {
                return false;
            }
        }
        return true;
    }

    // A hole reads as undefined here, unlike indexOf, so it is not skipped.
    private static boolean includes(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        if (length == 0) {
            return false;
        }
        final var search = arg(args, 0);
        final var from = toIntegerOrInfinity(arg(args, 1), ops);
        if (from == Double.POSITIVE_INFINITY) {
            return false;
        }
        for (var i = relativeIndex(from, length); i < length; i++) {
            if (SameValueZero.equal(target.get(i), search)) {
                return true;
            }
        }
        return false;
    }

    private static long indexOf(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        if (length == 0) {
            return -1;
        }
        final var search = arg(args, 0);
        final var from = toIntegerOrInfinity(arg(args, 1), ops);
        if (from == Double.POSITIVE_INFINITY) {
            return -1;
        }
        for (var i = relativeIndex(from, length); i < length; i++) {
            if (target.has(i) && JsOperators.strictEquals(target.get(i), search)) {
                return i;
            }
        }
        return -1;
    }

    private static long lastIndexOf(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        if (length == 0) {
            return -1;
        }
        final var search = arg(args, 0);
        final var from = args.size() > 1 ? toIntegerOrInfinity(args.get(1), ops) : (double) (length - 1);
        if (from == Double.NEGATIVE_INFINITY) {
            return -1;
        }
        final var start = from >= 0 ? (long) Math.min(from, (double) (length - 1)) : (long) (length + from);
        for (var i = start; i >= 0; i--) {
            if (target.has(i) && JsOperators.strictEquals(target.get(i), search)) {
                return i;
            }
        }
        return -1;
    }

    private static JsValue slice(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
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

    private static JsValue splice(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
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
        if (length + insertCount - deleteCount > MAX_SAFE_INTEGER) {
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

    private static void shiftForSplice(ArrayLike target, long length, long start, long deleteCount, long insertCount) {
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

    private static void moveOrDelete(ArrayLike target, long from, long to) {
        if (target.has(from)) {
            target.set(to, target.get(from));
        } else {
            target.delete(to);
        }
    }

    private static JsValue concat(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var result = speciesCreate(target, 0, ops);
        var written = 0L;
        final var items = new ArrayList<JsValue>(args.size() + 1);
        items.add(target.value);
        items.addAll(args);
        for (final var item : items) {
            if (isConcatSpreadable(item, ops)) {
                final var source = new ArrayLike(item, ops);
                final var length = source.length();
                if (written + length > MAX_SAFE_INTEGER) {
                    throw new TypeErrorException("Invalid array length");
                }
                for (var i = 0L; i < length; i++) {
                    if (source.has(i)) {
                        createDataPropertyOrThrow(result, written, source.get(i), ops);
                    }
                    written++;
                }
            } else {
                if (written >= MAX_SAFE_INTEGER) {
                    throw new TypeErrorException("Invalid array length");
                }
                createDataPropertyOrThrow(result, written, item, ops);
                written++;
            }
        }
        setResultLength(result, written, ops);
        return result;
    }

    private static boolean isConcatSpreadable(JsValue value, InterpreterOps ops) {
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

    // Get(O, @@isConcatSpreadable). The interpreter's symbol dispatch resolves an own symbol key only
    // on a plain object, so for every other exotic receiver the ordinary lookup is done here.
    private static JsValue concatSpreadableFlag(JsValue value, InterpreterOps ops) {
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

    private static String join(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
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
        }
        return sb.toString();
    }

    // Array.prototype.toString is generic: it calls whatever `join` the receiver resolves to, and
    // falls back to Object.prototype.toString when that is not callable.
    private static JsValue toStringMethod(ArrayLike target, Invoker invoker, InterpreterOps ops) {
        final var join = target.getKey(new JsString("join"));
        if (InterpreterUtils.isCallable(join)) {
            return invoker.call(join, target.value, List.of());
        }
        return invoker.call(ObjectProtoBuiltins.getMethod(target.value, "toString", ops, null), target.value,
                List.of());
    }

    private static JsValue push(ArrayLike target, List<JsValue> args) {
        var length = target.length();
        if (length + args.size() > MAX_SAFE_INTEGER) {
            throw new TypeErrorException("Invalid array length");
        }
        for (final var element : args) {
            target.set(length, element);
            length++;
        }
        target.setLength(length);
        return new JsNumber(length);
    }

    private static JsValue pop(ArrayLike target) {
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

    private static JsValue shift(ArrayLike target) {
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

    private static JsValue unshift(ArrayLike target, List<JsValue> args) {
        final var length = target.length();
        final var count = args.size();
        if (count > 0) {
            if (length + count > MAX_SAFE_INTEGER) {
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

    private static JsValue reverse(ArrayLike target) {
        final var length = target.length();
        for (var lower = 0L; lower < length / 2; lower++) {
            final var upper = length - lower - 1;
            final var lowerExists = target.has(lower);
            final var lowerValue = lowerExists ? target.get(lower) : null;
            final var upperExists = target.has(upper);
            final var upperValue = upperExists ? target.get(upper) : null;
            // Two indices that are both absent are left alone: only a present one is moved, and the
            // slot it vacates is deleted rather than filled with undefined.
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
        return target.value;
    }

    private static JsValue fill(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        final var value = arg(args, 0);
        final var start = relativeIndex(toIntegerOrInfinity(arg(args, 1), ops), length);
        final var end = args.size() < 3 || args.get(2) instanceof JsUndefined
                ? length
                : relativeIndex(toIntegerOrInfinity(args.get(2), ops), length);
        for (var i = start; i < end; i++) {
            target.set(i, value);
        }
        return target.value;
    }

    private static JsValue copyWithin(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
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
        return target.value;
    }

    private static JsValue at(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        final var relative = toIntegerOrInfinity(arg(args, 0), ops);
        final var index = relative >= 0 ? relative : length + relative;
        if (index < 0 || index >= length) {
            return JsUndefined.getInstance();
        }
        return target.get((long) index);
    }

    private static JsValue indexIterator(ArrayLike target, String kind) {
        // Once exhausted the iterator drops its receiver, so an element appended afterwards is never
        // visited even though the walk is otherwise live.
        final var exhausted = new boolean[1];
        return JsIterators.lazy(index -> {
            if (exhausted[0] || index >= target.length()) {
                exhausted[0] = true;
                return null;
            }
            return switch (kind) {
                case "keys" -> new JsNumber(index);
                case "entries" -> new JsArray(new ArrayList<>(List.of(new JsNumber(index), target.get(index))));
                default -> target.get(index);
            };
        });
    }

    private static JsValue sort(ArrayLike target, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var comparator = comparator(args);
        final var length = target.length();
        final var items = sortIndexedProperties(target, length, false);
        final var sorted = sorted(items, sortCompare(comparator, invoker, ops));
        for (var i = 0; i < sorted.size(); i++) {
            target.set(i, sorted.get(i));
        }
        for (var i = (long) sorted.size(); i < length; i++) {
            target.delete(i);
        }
        return target.value;
    }

    private static JsValue toSorted(ArrayLike target, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var comparator = comparator(args);
        final var length = target.length();
        final var sorted = sorted(sortIndexedProperties(target, length, true), sortCompare(comparator, invoker, ops));
        final var result = newArray(length);
        for (var i = 0; i < sorted.size(); i++) {
            createDataPropertyOrThrow(result, i, sorted.get(i), ops);
        }
        return result;
    }

    private static JsValue comparator(List<JsValue> args) {
        final var comparator = arg(args, 0);
        if (comparator instanceof JsUndefined) {
            return null;
        }
        if (!InterpreterUtils.isCallable(comparator)) {
            throw new TypeErrorException("The comparison function must be either a function or undefined");
        }
        return comparator;
    }

    private static List<JsValue> sortIndexedProperties(ArrayLike target, long length, boolean readThroughHoles) {
        if (length > Integer.MAX_VALUE) {
            throw new RangeErrorException("Invalid array length");
        }
        final var items = new ArrayList<JsValue>();
        for (var i = 0L; i < length; i++) {
            if (readThroughHoles || target.has(i)) {
                items.add(target.get(i));
            }
        }
        return items;
    }

    private static Comparator<JsValue> sortCompare(JsValue comparator, Invoker invoker, InterpreterOps ops) {
        return (left, right) -> {
            if (left instanceof JsUndefined || right instanceof JsUndefined) {
                if (left instanceof JsUndefined && right instanceof JsUndefined) {
                    return 0;
                }
                return left instanceof JsUndefined ? 1 : -1;
            }
            if (comparator != null) {
                final var result = JsCoercion
                        .toNumber(invoker.call(comparator, JsUndefined.getInstance(), List.of(left, right)), ops);
                if (Double.isNaN(result) || result == 0) {
                    return 0;
                }
                return result < 0 ? -1 : 1;
            }
            return JsCoercion.toStr(left, ops).compareTo(JsCoercion.toStr(right, ops));
        };
    }

    // A hand-rolled stable merge sort: a user comparator is free to be inconsistent, which
    // List.sort answers with an IllegalArgumentException rather than an arbitrary order.
    private static List<JsValue> sorted(List<JsValue> items, Comparator<JsValue> comparator) {
        if (items.size() < 2) {
            return items;
        }
        final var middle = items.size() / 2;
        final var left = sorted(new ArrayList<>(items.subList(0, middle)), comparator);
        final var right = sorted(new ArrayList<>(items.subList(middle, items.size())), comparator);
        final var merged = new ArrayList<JsValue>(items.size());
        var i = 0;
        var j = 0;
        while (i < left.size() && j < right.size()) {
            if (comparator.compare(right.get(j), left.get(i)) < 0) {
                merged.add(right.get(j++));
            } else {
                merged.add(left.get(i++));
            }
        }
        while (i < left.size()) {
            merged.add(left.get(i++));
        }
        while (j < right.size()) {
            merged.add(right.get(j++));
        }
        return merged;
    }

    private static JsValue flat(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        final var depth = args.isEmpty() || args.getFirst() instanceof JsUndefined
                ? 1
                : toIntegerOrInfinity(args.getFirst(), ops);
        final var result = speciesCreate(target, 0, ops);
        flattenInto(result, target, length, 0, depth, null, null, null, ops);
        return result;
    }

    private static JsValue flatMap(ArrayLike target, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        final var result = speciesCreate(target, 0, ops);
        flattenInto(result, target, length, 0, 1, callback, self, invoker, ops);
        return result;
    }

    private static long flattenInto(JsValue result, ArrayLike source, long length, long start, double depth,
            JsValue mapper, JsValue self, Invoker invoker, InterpreterOps ops) {
        var written = start;
        for (var i = 0L; i < length; i++) {
            if (!source.has(i)) {
                continue;
            }
            var element = source.get(i);
            if (mapper != null) {
                element = invoker.call(mapper, self, List.of(element, new JsNumber(i), source.value));
            }
            if (depth > 0 && isArray(element)) {
                final var nested = new ArrayLike(element, ops);
                written = flattenInto(result, nested, nested.length(), written, depth - 1, null, null, null, ops);
            } else {
                if (written >= MAX_SAFE_INTEGER) {
                    throw new TypeErrorException("Invalid array length");
                }
                createDataPropertyOrThrow(result, written, element, ops);
                written++;
            }
        }
        return written;
    }

    private static JsValue toReversed(ArrayLike target, InterpreterOps ops) {
        final var length = target.length();
        final var result = newArray(length);
        for (var i = 0L; i < length; i++) {
            createDataPropertyOrThrow(result, i, target.get(length - i - 1), ops);
        }
        return result;
    }

    private static JsValue toSpliced(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        final var start = relativeIndex(toIntegerOrInfinity(arg(args, 0), ops), length);
        final var insertCount = Math.max(args.size() - 2, 0);
        final long skipCount;
        if (args.isEmpty()) {
            skipCount = 0;
        } else if (args.size() == 1) {
            skipCount = length - start;
        } else {
            skipCount = (long) Math.clamp(toIntegerOrInfinity(args.get(1), ops), 0, (double) (length - start));
        }
        final var newLength = length + insertCount - skipCount;
        if (newLength > MAX_SAFE_INTEGER) {
            throw new TypeErrorException("Invalid array length");
        }
        final var result = newArray(newLength);
        var written = 0L;
        while (written < start) {
            createDataPropertyOrThrow(result, written, target.get(written), ops);
            written++;
        }
        for (var i = 0; i < insertCount; i++) {
            createDataPropertyOrThrow(result, written, args.get(i + 2), ops);
            written++;
        }
        var read = start + skipCount;
        while (written < newLength) {
            createDataPropertyOrThrow(result, written, target.get(read), ops);
            written++;
            read++;
        }
        return result;
    }

    private static JsValue with(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        final var relative = toIntegerOrInfinity(arg(args, 0), ops);
        final var index = relative >= 0 ? relative : length + relative;
        if (index < 0 || index >= length) {
            throw new RangeErrorException("Invalid index : " + relative);
        }
        final var replacement = arg(args, 1);
        final var result = newArray(length);
        for (var i = 0L; i < length; i++) {
            createDataPropertyOrThrow(result, i, i == (long) index ? replacement : target.get(i), ops);
        }
        return result;
    }

    private static JsValue callback(List<JsValue> args) {
        final var fn = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        if (!InterpreterUtils.isCallable(fn)) {
            throw new TypeErrorException(JsCoercion.toStr(fn) + " is not a function");
        }
        return fn;
    }

    private static JsValue thisArg(List<JsValue> args) {
        return args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
    }
}
