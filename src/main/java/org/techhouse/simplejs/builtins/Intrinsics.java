package org.techhouse.simplejs.builtins;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// The realm's intrinsic prototype objects. Deliberately per-Interpreter (never static): a shared
// Array.prototype would let one script's monkey-patch leak into every later script in the JVM.
public final class Intrinsics {
    @FunctionalInterface
    private interface MethodResolver {
        JsValue resolve(JsValue receiver, String name);
    }

    private static final PropertyFlags HIDDEN = new PropertyFlags(true, false, true);
    private static final Set<String> MUTATING_ARRAY_METHODS = Set.of("push", "pop", "shift", "unshift", "splice",
            "copyWithin", "fill", "reverse", "sort");
    private static final Set<String> REVERSE_ARRAY_METHODS = Set.of("lastIndexOf", "reduceRight", "findLast",
            "findLastIndex");
    // Methods that stop early or deliberately skip an index: materialising every index getter up
    // front would run side effects the spec says never happen.
    private static final Set<String> SHORT_CIRCUITING_ARRAY_METHODS = Set.of("indexOf", "lastIndexOf", "includes",
            "with");
    private static final List<String> ERROR_NAMES = List.of("Error", "TypeError", "RangeError", "SyntaxError",
            "URIError", "ReferenceError", "EvalError", "SuppressedError", "AggregateError");

    private final Invoker invoker;
    private final InterpreterOps ops;

    private final JsObject objectProto = new JsObject();
    private final JsObject functionProto;
    private final JsObject arrayProto;
    private final JsObject stringProto;
    private final JsObject numberProto;
    private final JsObject booleanProto;
    private final JsObject bigintProto;
    private final JsObject symbolProto;
    private final JsObject regexpProto;
    private final JsObject mapProto;
    private final JsObject weakMapProto;
    private final JsObject setProto;
    private final JsObject weakSetProto;
    private final JsObject dateProto;
    private final JsObject promiseProto;
    private final JsObject iteratorProto;
    private final JsObject asyncIteratorProto;
    private final JsObject arrayBufferProto;
    private final JsObject dataViewProto;
    private final Map<JsTypedArray.Kind, JsObject> typedArrayProtos = new EnumMap<>(JsTypedArray.Kind.class);
    private final JsObject typedArrayProto;
    private final JsObject disposableStackProto;
    private final JsObject asyncDisposableStackProto;
    private final JsObject errorProto = new JsObject();
    private JsValue defaultHasInstance;
    private JsValue defaultArrayIterator;
    private JsValue defaultStringIterator;
    private JsValue defaultTypedArrayIterator;
    private final Map<String, JsObject> errorProtos = new LinkedHashMap<>();

    public Intrinsics(Invoker invoker, InterpreterOps ops, EventLoop eventLoop, GeneratorBuiltins.AsyncDriver driver) {
        this.invoker = invoker;
        this.ops = ops;
        // Unlike the other prototypes, the receiver is handed to PromiseBuiltins unchecked: only
        // `then` carries the [[PromiseState]] brand check, while `catch`/`finally` are generic and
        // must work on any thenable.
        promiseProto = prototypeOf(PromiseBuiltins.PROTO_NAMES, "Promise.prototype",
                (receiver, name) -> PromiseBuiltins.getMethod(receiver, name, eventLoop, invoker, this));
        iteratorProto = prototypeOf(GeneratorBuiltins.PROTO_NAMES, "Generator.prototype",
                (receiver, name) -> GeneratorBuiltins.getMethod(requireGenerator(receiver, name), name));
        asyncIteratorProto = prototypeOf(GeneratorBuiltins.PROTO_NAMES, "AsyncGenerator.prototype", (receiver,
                name) -> GeneratorBuiltins.getAsyncMethod(requireAsyncGenerator(receiver, name), name, driver));
        functionProto = prototypeOf(FunctionProtoBuiltins.NAMES, "Function.prototype", (receiver,
                name) -> FunctionProtoBuiltins.getMethod(requireCallable(receiver, name), name, invoker, ops));
        installFunctionHasInstance(functionProto);
        arrayProto = arrayPrototype();
        stringProto = prototypeOf(StringBuiltins.NAMES, "String.prototype",
                (receiver, name) -> StringBuiltins.getMethod(requireString(receiver, name), name, invoker, ops));
        numberProto = prototypeOf(NumberBuiltins.NAMES, "Number.prototype",
                (receiver, name) -> NumberBuiltins.getMethod(requireNumber(receiver, name), name));
        booleanProto = booleanPrototype();
        bigintProto = prototypeOf(BigIntBuiltins.NAMES, "BigInt.prototype",
                (receiver, name) -> BigIntBuiltins.getMethod(requireBigInt(receiver, name), name));
        symbolProto = prototypeOf(SymbolBuiltins.NAMES, "Symbol.prototype",
                (receiver, name) -> SymbolBuiltins.getMethod(requireSymbol(receiver, name), name));
        installSymbolAccessors(symbolProto);
        regexpProto = prototypeOf(RegexBuiltins.NAMES, "RegExp.prototype",
                (receiver, name) -> RegexBuiltins.getMethod(requireRegExp(receiver, name), name));
        installRegExpSymbolMethods(regexpProto);
        installRegExpAccessors(regexpProto);
        mapProto = prototypeOf(MapBuiltins.NAMES, "Map.prototype",
                (receiver, name) -> MapBuiltins.getMethod(requireMap(receiver, name, false), name, invoker));
        weakMapProto = prototypeOf(MapBuiltins.WEAK_NAMES, "WeakMap.prototype",
                (receiver, name) -> MapBuiltins.getMethod(requireMap(receiver, name, true), name, invoker));
        setProto = prototypeOf(SetBuiltins.NAMES, "Set.prototype",
                (receiver, name) -> SetBuiltins.getMethod(requireSet(receiver, name, false), name, invoker, ops));
        weakSetProto = prototypeOf(SetBuiltins.WEAK_NAMES, "WeakSet.prototype",
                (receiver, name) -> SetBuiltins.getMethod(requireSet(receiver, name, true), name, invoker, ops));
        dateProto = prototypeOf(DateBuiltins.NAMES, "Date.prototype",
                (receiver, name) -> DateBuiltins.getMethod(requireDate(receiver, name), name, ops));
        defineSymbol(dateProto, JsSymbol.TO_PRIMITIVE, DateBuiltins.symbolToPrimitive(ops));
        arrayBufferProto = prototypeOf(TypedArrayBuiltins.BUFFER_NAMES, "ArrayBuffer.prototype",
                (receiver, name) -> TypedArrayBuiltins.bufferMethod(requireBuffer(receiver, name), name));
        dataViewProto = prototypeOf(TypedArrayBuiltins.VIEW_NAMES, "DataView.prototype",
                (receiver, name) -> TypedArrayBuiltins.dataViewMethod(requireView(receiver, name), name));
        typedArrayProto = prototypeOf(TypedArrayBuiltins.NAMES, "TypedArray.prototype", (receiver,
                name) -> TypedArrayBuiltins.getMethod(requireTypedArray(receiver, name), name, invoker, ops));
        // Per spec, %TypedArray%.prototype[Symbol.iterator] is the very same function object as
        // %TypedArray%.prototype.values (not just an equivalent one).
        defineSymbol(typedArrayProto, JsSymbol.ITERATOR, typedArrayProto.get("values"));
        // Real accessor properties (not just the fast-dispatch special-casing MemberEvaluator does
        // for actual JsTypedArray receivers) so `TypedArray.prototype.length` etc, invoked with a
        // non-typed-array `this`, throws per spec instead of silently reading through as undefined.
        installTypedArrayGeometryAccessors(typedArrayProto);
        installGeometryAccessors(arrayBufferProto, TypedArrayBuiltins.bufferAccessorNames(),
                (thisArg, name) -> TypedArrayBuiltins.bufferMethod(requireBuffer(thisArg, name), name));
        installGeometryAccessors(dataViewProto, TypedArrayBuiltins.viewAccessorNames(),
                (thisArg, name) -> TypedArrayBuiltins.dataViewMethod(requireView(thisArg, name), name));
        for (final var kind : JsTypedArray.Kind.values()) {
            final var proto = new JsObject();
            proto.setProto(typedArrayProto);
            typedArrayProtos.put(kind, proto);
        }
        for (final var name : TypedArrayBuiltins.UINT8_NAMES) {
            define(typedArrayProtos.get(JsTypedArray.Kind.UINT8), name, wrapper(name, "Uint8Array.prototype",
                    (receiver, key) -> TypedArrayBuiltins.uint8Method(requireUint8(receiver, key), key)));
        }
        disposableStackProto = prototypeOf(DisposableStackBuiltins.NAMES, "DisposableStack.prototype",
                (receiver, name) -> DisposableStackBuiltins.getMethod(requireStack(receiver, name), name, ops, invoker,
                        eventLoop, false));
        DisposableStackBuiltins.installAccessors(disposableStackProto, false);
        asyncDisposableStackProto = prototypeOf(DisposableStackBuiltins.ASYNC_NAMES, "AsyncDisposableStack.prototype",
                (receiver, name) -> DisposableStackBuiltins.getMethod(requireStack(receiver, name), name, ops, invoker,
                        eventLoop, true));
        DisposableStackBuiltins.installAccessors(asyncDisposableStackProto, true);
        installObjectPrototype();
        installErrorPrototypes();
        installIteratorSymbols();
    }

    // Per spec these are the very same function objects as the named methods they alias, so a script
    // can compare, replace or delete them and every iteration site has to observe the change.
    private void installIteratorSymbols() {
        defineSymbol(arrayProto, JsSymbol.ITERATOR, arrayProto.get("values"));
        defineSymbol(mapProto, JsSymbol.ITERATOR, mapProto.get("entries"));
        defineSymbol(setProto, JsSymbol.ITERATOR, setProto.get("values"));
        defineSymbol(stringProto, JsSymbol.ITERATOR,
                new JsNativeFunction("[Symbol.iterator]", (thisArg,
                        _) -> JsIterators.of(InterpreterUtils
                                .stringCodePoints(JsCoercion.toStr(requireString(thisArg, "[Symbol.iterator]"), ops))
                                .iterator())));
        defineSymbol(iteratorProto, JsSymbol.ITERATOR,
                new JsNativeFunction("[Symbol.iterator]", (thisArg, _) -> thisArg));
        defineSymbol(asyncIteratorProto, JsSymbol.ASYNC_ITERATOR,
                new JsNativeFunction("[Symbol.asyncIterator]", (thisArg, _) -> thisArg));
        defineToStringTag(promiseProto, "Promise");
        defineToStringTag(asyncIteratorProto, "AsyncGenerator");
        defaultArrayIterator = arrayProto.getSymbol(JsSymbol.ITERATOR);
        defaultStringIterator = stringProto.getSymbol(JsSymbol.ITERATOR);
        defaultTypedArrayIterator = typedArrayProto.getSymbol(JsSymbol.ITERATOR);
    }

    // `description` is an accessor on Symbol.prototype, not a per-symbol own property, and
    // Symbol.prototype[Symbol.toPrimitive] is what keeps `+sym` from coercing through toString.
    private void installSymbolAccessors(JsObject proto) {
        final var getter = new JsNativeFunction("get description",
                (thisArg, _) -> SymbolBuiltins.descriptionOf(requireSymbol(thisArg, "description")));
        getter.setLength(0);
        proto.defineAccessor("description", getter, null);
        proto.setFlags("description", HIDDEN);
        final var toPrimitive = new JsNativeFunction("[Symbol.toPrimitive]",
                (thisArg, _) -> requireSymbol(thisArg, "[Symbol.toPrimitive]"));
        toPrimitive.setLength(1);
        proto.setSymbol(JsSymbol.TO_PRIMITIVE, toPrimitive);
        proto.setSymbolFlags(JsSymbol.TO_PRIMITIVE, new PropertyFlags(false, false, true));
        proto.setSymbol(JsSymbol.TO_STRING_TAG, new JsString("Symbol"));
        proto.setSymbolFlags(JsSymbol.TO_STRING_TAG, new PropertyFlags(false, false, true));
    }

    // The interpreter's own `instanceof` keeps its faster brand-aware path, so this exists for a
    // direct call and for a script that reads it off Function.prototype; isDefaultHasInstance lets
    // the operator tell the intrinsic apart from a user-installed override.
    private void installFunctionHasInstance(JsObject proto) {
        final var hasInstance = new JsNativeFunction("[Symbol.hasInstance]", (thisArg, args) -> FunctionProtoBuiltins
                .ordinaryHasInstance(thisArg, args.isEmpty() ? JsUndefined.getInstance() : args.getFirst(), ops));
        hasInstance.setLength(1);
        proto.setSymbol(JsSymbol.HAS_INSTANCE, hasInstance);
        proto.setSymbolFlags(JsSymbol.HAS_INSTANCE, new PropertyFlags(false, false, false));
        defaultHasInstance = hasInstance;
    }

    public boolean isDefaultHasInstance(JsValue candidate) {
        return candidate == defaultHasInstance;
    }

    private static void defineSymbol(JsObject target, JsSymbol key, JsValue value) {
        target.setSymbol(key, value);
        target.setSymbolFlags(key, HIDDEN);
    }

    private static void defineToStringTag(JsObject target, String tag) {
        target.setSymbol(JsSymbol.TO_STRING_TAG, new JsString(tag));
        target.setSymbolFlags(JsSymbol.TO_STRING_TAG, new PropertyFlags(false, false, true));
    }

    // The array-like values iterate straight out of their backing storage; that shortcut is only
    // legal while their @@iterator is still the intrinsic one a script has not replaced.
    public boolean isDefaultIterator(JsValue target, JsValue candidate) {
        final var expected = switch (target) {
            case JsArray ignored -> defaultArrayIterator;
            case JsArguments ignored -> defaultArrayIterator;
            case JsString ignored -> defaultStringIterator;
            case JsTypedArray ignored -> defaultTypedArrayIterator;
            default -> null;
        };
        return expected != null && candidate == expected;
    }

    private void installObjectPrototype() {
        for (final var name : ObjectProtoBuiltins.NAMES) {
            define(objectProto, name, wrapper(name, "Object.prototype",
                    (receiver, key) -> ObjectProtoBuiltins.getMethod(receiver, key, ops, this)));
        }
        errorProto.setProto(objectProto);
    }

    private void installErrorPrototypes() {
        define(errorProto, "toString",
                new JsNativeFunction("toString", (thisArg, _) -> new JsString(errorText(requireObject(thisArg)))));
        ErrorBuiltins.installStackAccessor(errorProto, ops);
        // Error.prototype is the shared base so `e instanceof Error` holds for every error subtype.
        define(errorProto, "name", new JsString("Error"));
        define(errorProto, "message", new JsString(""));
        errorProtos.put("Error", errorProto);
        for (final var name : ERROR_NAMES) {
            if (errorProtos.containsKey(name)) {
                continue;
            }
            final var proto = new JsObject();
            proto.setProto(errorProto);
            define(proto, "name", new JsString(name));
            define(proto, "message", new JsString(""));
            errorProtos.put(name, proto);
        }
    }

    private static String errorText(JsObject error) {
        final var name = error.has("name") ? JsCoercion.toStr(error.get("name")) : "Error";
        final var message = error.has("message") ? JsCoercion.toStr(error.get("message")) : "";
        return message.isEmpty() ? name : name + ": " + message;
    }

    private JsObject booleanPrototype() {
        final var proto = new JsObject();
        define(proto, "toString", new JsNativeFunction("toString",
                (thisArg, _) -> new JsString(JsCoercion.toStr(requireBoolean(thisArg, "toString")))));
        define(proto, "valueOf", new JsNativeFunction("valueOf", (thisArg, _) -> requireBoolean(thisArg, "valueOf")));
        proto.setProto(objectProto);
        return proto;
    }

    // Array.prototype needs its own wrapper rather than the shared one: a generic array-like
    // receiver is snapshotted into a JsArray to run the method against, so a *mutating* method's
    // effects have to be copied back onto the real receiver afterwards or they are silently lost.
    private JsObject arrayPrototype() {
        final var proto = new JsObject();
        for (final var name : ArrayBuiltins.NAMES) {
            final var method = new JsNativeFunction(name, (thisArg, args) -> callArrayMethod(thisArg, name, args));
            method.setLength(BuiltinLengths.lengthOf("Array.prototype", name));
            define(proto, name, method);
        }
        proto.setProto(objectProto);
        return proto;
    }

    private JsValue callArrayMethod(JsValue thisArg, String name, List<JsValue> args) {
        final var target = SHORT_CIRCUITING_ARRAY_METHODS.contains(name)
                ? requireArray(thisArg, name)
                : materializeAccessors(requireArray(thisArg, name));
        final var resolved = ArrayBuiltins.getMethod(target, name, invoker, ops);
        if (resolved == null) {
            throw incompatible("Array.prototype." + name, thisArg);
        }
        final var snapshot = target != thisArg && unwrap(thisArg) != target;
        final var before = snapshot ? target.getElements().size() : 0;
        final var result = invoker.call(resolved, thisArg, args);
        if (snapshot && MUTATING_ARRAY_METHODS.contains(name)) {
            writeBackArray(thisArg, target, before);
        }
        return result;
    }

    // A rejected [[Set]]/[[Delete]] on the receiver is a TypeError, not a silent skip: the whole
    // point of the writeback is that a mutating method on a frozen/read-only array-like still fails
    // the way it would on a real array.
    // ArrayBuiltins reads the backing element list directly, which would skip a getter installed on
    // an index by defineProperty. Invoking those getters in ascending index order up front matches
    // the spec's own read order closely enough that a getter which installs a later index's getter
    // (a shape the corpus tests repeatedly) still observes it.
    private JsArray materializeAccessors(JsArray target) {
        if (!target.hasAnyIndexAccessor()) {
            return target;
        }
        final var length = target.getElements().size();
        final var materialized = new JsArray();
        for (var i = 0; i < length; i++) {
            final var getter = target.getIndexAccessorGetter(i);
            if (getter != null) {
                materialized.defineIndexValue(i, invoker.call(getter, target, List.of()));
            } else if (!target.isHole(i)) {
                materialized.defineIndexValue(i, target.get(i));
            }
        }
        materialized.setLength(length);
        return materialized;
    }

    private void writeBackArray(JsValue receiver, JsArray target, int before) {
        final var after = target.getElements().size();
        for (var i = 0; i < after; i++) {
            writeBackIndex(receiver, String.valueOf(i), target.get(i));
        }
        for (var i = after; i < before; i++) {
            final var key = new JsString(String.valueOf(i));
            if (!ops.deleteMember(receiver, key)) {
                throw new TypeErrorException("Cannot delete property '" + i + "' of the receiver");
            }
        }
        writeBackIndex(receiver, "length", new JsNumber(after));
    }

    private void writeBackIndex(JsValue receiver, String key, JsValue value) {
        if (!ops.setMember(receiver, new JsString(key), value)) {
            throw new TypeErrorException("Cannot assign to read only property '" + key + "' of the receiver");
        }
    }

    private JsObject prototypeOf(List<String> names, String label, MethodResolver resolver) {
        final var proto = new JsObject();
        for (final var name : names) {
            define(proto, name, wrapper(name, label, resolver));
        }
        proto.setProto(objectProto);
        return proto;
    }

    private JsNativeFunction wrapper(String name, String label, MethodResolver resolver) {
        final var wrapped = new JsNativeFunction(name, (thisArg, args) -> {
            final var method = resolver.resolve(thisArg, name);
            if (method == null) {
                throw incompatible(label + "." + name, thisArg);
            }
            return invoker.call(method, thisArg, args);
        });
        wrapped.setLength(BuiltinLengths.lengthOf(label, name));
        return wrapped;
    }

    private static void define(JsObject target, String key, JsValue value) {
        target.defineValue(key, value);
        target.setFlags(key, HIDDEN);
    }

    public JsObject protoFor(JsValue value) {
        return switch (value) {
            case JsArray ignored -> arrayProto;
            case JsString ignored -> stringProto;
            case JsNumber ignored -> numberProto;
            case JsBoolean ignored -> booleanProto;
            case JsBigInt ignored -> bigintProto;
            case JsSymbol ignored -> symbolProto;
            case JsRegExp ignored -> regexpProto;
            case JsMap map -> map.isWeak() ? weakMapProto : mapProto;
            case JsSet set -> set.isWeak() ? weakSetProto : setProto;
            case JsDate ignored -> dateProto;
            case JsPromise ignored -> promiseProto;
            case JsGenerator ignored -> iteratorProto;
            case JsAsyncGenerator ignored -> asyncIteratorProto;
            case JsArrayBuffer ignored -> arrayBufferProto;
            case JsDataView ignored -> dataViewProto;
            case JsTypedArray typed -> typedArrayProtos.get(typed.kind());
            case JsFunction ignored -> functionProto;
            case JsNativeFunction ignored -> functionProto;
            default -> objectProto;
        };
    }

    public JsObject disposableStackProto() {
        return disposableStackProto;
    }

    public JsObject asyncDisposableStackProto() {
        return asyncDisposableStackProto;
    }

    public JsObject objectProto() {
        return objectProto;
    }

    public JsObject functionProto() {
        return functionProto;
    }

    public JsObject arrayProto() {
        return arrayProto;
    }

    public JsObject stringProto() {
        return stringProto;
    }

    public JsObject numberProto() {
        return numberProto;
    }

    public JsObject booleanProto() {
        return booleanProto;
    }

    public JsObject bigintProto() {
        return bigintProto;
    }

    public JsObject symbolProto() {
        return symbolProto;
    }

    public JsObject regexpProto() {
        return regexpProto;
    }

    public JsObject mapProto() {
        return mapProto;
    }

    public JsObject weakMapProto() {
        return weakMapProto;
    }

    public JsObject setProto() {
        return setProto;
    }

    public JsObject weakSetProto() {
        return weakSetProto;
    }

    public JsObject dateProto() {
        return dateProto;
    }

    public JsObject promiseProto() {
        return promiseProto;
    }

    public JsObject iteratorProto() {
        return iteratorProto;
    }

    public JsObject asyncIteratorProto() {
        return asyncIteratorProto;
    }

    public JsObject arrayBufferProto() {
        return arrayBufferProto;
    }

    public JsObject dataViewProto() {
        return dataViewProto;
    }

    public JsObject typedArrayProto(JsTypedArray.Kind kind) {
        return typedArrayProtos.get(kind);
    }

    public JsObject typedArrayProto() {
        return typedArrayProto;
    }

    public JsObject errorProto(String name) {
        return errorProtos.getOrDefault(name, errorProto);
    }

    public JsObject makeError(String name, String message) {
        return ErrorBuiltins.makeError(name, message, errorProto(name));
    }

    // Array.prototype.* accepts any array-like receiver by snapshotting it into a JsArray; a
    // mutating method therefore does not write through to the original (documented limitation), so a
    // plain object — unlike arguments/typed arrays/strings, which callers already treat as read-only
    // views — is accepted only for the non-mutating methods rather than silently losing the write.
    private JsArray requireArray(JsValue receiver, String method) {
        if (receiver instanceof JsArray array) {
            return array;
        }
        if (unwrap(receiver) instanceof JsArray wrapped) {
            return wrapped;
        }
        if (receiver instanceof JsArguments || receiver instanceof JsTypedArray || receiver instanceof JsString) {
            return new JsArray(InterpreterUtils.arrayLikeElements(receiver));
        }
        // A generic (non-mutating) Array method works on any object per spec, treating a missing
        // or non-numeric "length" as 0 (LengthOfArrayLike -> ToLength) rather than requiring the
        // property to already be present - e.g. `every`/`map`/`forEach`.call({}, ...) is valid and
        // vacuously iterates zero elements, it does not reject the receiver.
        if ((receiver instanceof JsObject || receiver instanceof JsProxy) && ops != null) {
            return new JsArray(
                    InterpreterUtils.arrayLikeElements(receiver, ops, REVERSE_ARRAY_METHODS.contains(method)));
        }
        // ToObject on a raw primitive (other than null/undefined, which ToObject rejects) succeeds
        // and yields a wrapper with no own `length`/indexed properties, i.e. an empty array-like.
        if (receiver instanceof JsBoolean || receiver instanceof JsNumber || receiver instanceof JsBigInt
                || receiver instanceof JsSymbol) {
            return new JsArray();
        }
        throw incompatible("Array.prototype." + method, receiver);
    }

    // Every String.prototype method is generic per spec: RequireObjectCoercible(this value) then
    // ToString(this value) - there is no requirement that the receiver already be a string, so a
    // number/object/etc. receiver is coerced rather than rejected. Only null/undefined throw.
    private JsString requireString(JsValue receiver, String method) {
        if (receiver instanceof JsString string) {
            return string;
        }
        if (unwrap(receiver) instanceof JsString wrapped) {
            return wrapped;
        }
        if (receiver instanceof JsNull || receiver instanceof JsUndefined) {
            throw incompatible("String.prototype." + method, receiver);
        }
        return new JsString(JsCoercion.toStr(receiver, ops));
    }

    private JsNumber requireNumber(JsValue receiver, String method) {
        if (receiver instanceof JsNumber number) {
            return number;
        }
        if (unwrap(receiver) instanceof JsNumber wrapped) {
            return wrapped;
        }
        throw incompatible("Number.prototype." + method, receiver);
    }

    private JsBoolean requireBoolean(JsValue receiver, String method) {
        if (receiver instanceof JsBoolean bool) {
            return bool;
        }
        if (unwrap(receiver) instanceof JsBoolean wrapped) {
            return wrapped;
        }
        throw incompatible("Boolean.prototype." + method, receiver);
    }

    private JsBigInt requireBigInt(JsValue receiver, String method) {
        if (receiver instanceof JsBigInt bigInt) {
            return bigInt;
        }
        if (unwrap(receiver) instanceof JsBigInt wrapped) {
            return wrapped;
        }
        throw incompatible("BigInt.prototype." + method, receiver);
    }

    private JsSymbol requireSymbol(JsValue receiver, String method) {
        if (receiver instanceof JsSymbol symbol) {
            return symbol;
        }
        if (unwrap(receiver) instanceof JsSymbol wrapped) {
            return wrapped;
        }
        throw incompatible("Symbol.prototype." + method, receiver);
    }

    private JsRegExp requireRegExp(JsValue receiver, String method) {
        if (receiver instanceof JsRegExp regexp) {
            return regexp;
        }
        if (unwrap(receiver) instanceof JsRegExp wrapped) {
            return wrapped;
        }
        throw incompatible("RegExp.prototype." + method, receiver);
    }

    private void installRegExpAccessors(JsObject proto) {
        for (final var name : RegexBuiltins.PROTO_ACCESSORS) {
            final var getter = new JsNativeFunction("get " + name,
                    (thisArg, _) -> RegexBuiltins.protoAccessor(regExpReceiver(thisArg), name, proto));
            proto.defineAccessor(name, getter, null);
            proto.setFlags(name, HIDDEN);
        }
    }

    // A `class extends RegExp` instance keeps its JsRegExp state in the wrapped primitive slot, so
    // the accessor has to unwrap before deciding the receiver is incompatible.
    private JsValue regExpReceiver(JsValue receiver) {
        return receiver instanceof JsRegExp ? receiver : orSelf(unwrap(receiver), receiver);
    }

    private static JsValue orSelf(JsValue unwrapped, JsValue receiver) {
        return unwrapped instanceof JsRegExp ? unwrapped : receiver;
    }

    // Installed as real callable symbol-keyed methods (not just consulted internally by
    // String.prototype's delegation) so a direct call/exposure via RegExp.prototype[Symbol.match]
    // etc. works, per docs/simplejs.md's "well-known symbol hooks" note.

    private void installRegExpSymbolMethods(JsObject proto) {
        final var match = new JsNativeFunction("[Symbol.match]",
                (thisArg, args) -> RegexBuiltins.symbolMatch(thisArg, argStr(args), ops));
        match.setLength(1);
        proto.setSymbol(JsSymbol.MATCH, match);
        final var search = new JsNativeFunction("[Symbol.search]",
                (thisArg, args) -> RegexBuiltins.symbolSearch(thisArg, argStr(args), ops));
        search.setLength(1);
        proto.setSymbol(JsSymbol.SEARCH, search);
        final var replace = new JsNativeFunction("[Symbol.replace]",
                (thisArg, args) -> RegexBuiltins.symbolReplace(thisArg, argStr(args),
                        args.size() > 1 ? args.get(1) : JsUndefined.getInstance(), ops, invoker));
        replace.setLength(2);
        proto.setSymbol(JsSymbol.REPLACE, replace);
        final var split = new JsNativeFunction("[Symbol.split]", (thisArg, args) -> RegexBuiltins.symbolSplit(thisArg,
                argStr(args), args.size() > 1 ? args.get(1) : JsUndefined.getInstance(), ops));
        split.setLength(2);
        proto.setSymbol(JsSymbol.SPLIT, split);
    }

    private String argStr(List<JsValue> args) {
        return !args.isEmpty() ? JsCoercion.toStr(args.getFirst(), ops) : "undefined";
    }

    private JsGenerator requireGenerator(JsValue receiver, String method) {
        if (receiver instanceof JsGenerator generator) {
            return generator;
        }
        throw incompatible("Generator.prototype." + method, receiver);
    }

    private JsAsyncGenerator requireAsyncGenerator(JsValue receiver, String method) {
        if (receiver instanceof JsAsyncGenerator generator) {
            return generator;
        }
        throw incompatible("AsyncGenerator.prototype." + method, receiver);
    }

    private JsMap requireMap(JsValue receiver, String method, boolean weak) {
        if (receiver instanceof JsMap map && map.isWeak() == weak) {
            return map;
        }
        if (unwrap(receiver) instanceof JsMap wrapped && wrapped.isWeak() == weak) {
            return wrapped;
        }
        throw incompatible((weak ? "WeakMap.prototype." : "Map.prototype.") + method, receiver);
    }

    private JsSet requireSet(JsValue receiver, String method, boolean weak) {
        if (receiver instanceof JsSet set && set.isWeak() == weak) {
            return set;
        }
        if (unwrap(receiver) instanceof JsSet wrapped && wrapped.isWeak() == weak) {
            return wrapped;
        }
        throw incompatible((weak ? "WeakSet.prototype." : "Set.prototype.") + method, receiver);
    }

    private JsDate requireDate(JsValue receiver, String method) {
        if (receiver instanceof JsDate date) {
            return date;
        }
        if (unwrap(receiver) instanceof JsDate wrapped) {
            return wrapped;
        }
        throw incompatible("Date.prototype." + method, receiver);
    }

    private JsArrayBuffer requireBuffer(JsValue receiver, String method) {
        if (receiver instanceof JsArrayBuffer buffer) {
            return buffer;
        }
        if (unwrap(receiver) instanceof JsArrayBuffer wrapped) {
            return wrapped;
        }
        throw incompatible("ArrayBuffer.prototype." + method, receiver);
    }

    private JsDataView requireView(JsValue receiver, String method) {
        if (receiver instanceof JsDataView view) {
            return view;
        }
        if (unwrap(receiver) instanceof JsDataView wrapped) {
            return wrapped;
        }
        throw incompatible("DataView.prototype." + method, receiver);
    }

    // ArrayBuffer/DataView geometry are accessor properties on the prototype, not data methods, so
    // reading one off a foreign receiver has to throw rather than resolve to undefined.
    private void installGeometryAccessors(JsObject proto, List<String> names, MethodResolver resolver) {
        for (final var name : names) {
            proto.defineAccessor(name,
                    new JsNativeFunction("get " + name, (thisArg, _) -> resolver.resolve(thisArg, name)), null);
            proto.setFlags(name, HIDDEN);
        }
    }

    private void installTypedArrayGeometryAccessors(JsObject proto) {
        proto.defineAccessor("length", new JsNativeFunction("get length",
                (thisArg, _) -> new JsNumber(requireTypedArray(thisArg, "length").length())), null);
        proto.defineAccessor("byteLength", new JsNativeFunction("get byteLength",
                (thisArg, _) -> new JsNumber(requireTypedArray(thisArg, "byteLength").byteLength())), null);
        proto.defineAccessor("byteOffset", new JsNativeFunction("get byteOffset",
                (thisArg, _) -> new JsNumber(requireTypedArray(thisArg, "byteOffset").byteOffset())), null);
        proto.defineAccessor("buffer",
                new JsNativeFunction("get buffer", (thisArg, _) -> requireTypedArray(thisArg, "buffer").getBuffer()),
                null);
        for (final var name : List.of("length", "byteLength", "byteOffset", "buffer")) {
            proto.setFlags(name, new JsObject.PropertyFlags(true, false, true));
        }
    }

    private JsTypedArray requireTypedArray(JsValue receiver, String method) {
        if (receiver instanceof JsTypedArray typed) {
            return typed;
        }
        if (unwrap(receiver) instanceof JsTypedArray wrapped) {
            return wrapped;
        }
        throw incompatible("TypedArray.prototype." + method, receiver);
    }

    private JsTypedArray requireUint8(JsValue receiver, String method) {
        final var typed = requireTypedArray(receiver, method);
        if (typed.kind() != JsTypedArray.Kind.UINT8) {
            throw incompatible("Uint8Array.prototype." + method, receiver);
        }
        return typed;
    }

    private JsObject requireStack(JsValue receiver, String method) {
        if (receiver instanceof JsObject object && object.hasSymbol(DisposableStackBuiltins.entriesKey())) {
            return object;
        }
        throw incompatible("DisposableStack.prototype." + method, receiver);
    }

    private JsObject requireObject(JsValue receiver) {
        if (receiver instanceof JsObject object) {
            return object;
        }
        throw incompatible("Object.prototype." + "toString", receiver);
    }

    private JsValue requireCallable(JsValue receiver, String method) {
        if (receiver instanceof JsFunction || receiver instanceof JsNativeFunction || receiver instanceof JsClass) {
            return receiver;
        }
        throw incompatible("Function" + ".prototype." + method, receiver);
    }

    private static JsValue unwrap(JsValue receiver) {
        return receiver instanceof JsObject object ? object.getPrimitive() : null;
    }

    private static TypeErrorException incompatible(String method, JsValue receiver) {
        return new TypeErrorException(method + " called on an incompatible receiver " + JsCoercion.toStr(receiver));
    }
}
