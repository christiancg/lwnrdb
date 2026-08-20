package org.techhouse.simplejs.builtins;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
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
    private static final List<String> ERROR_NAMES = List.of("Error", "TypeError", "RangeError", "SyntaxError",
            "URIError", "ReferenceError", "EvalError", "SuppressedError", "AggregateError");
    private static final List<String> UNSCOPABLE_ARRAY_METHODS = List.of("at", "copyWithin", "entries", "fill", "find",
            "findIndex", "findLast", "findLastIndex", "flat", "flatMap", "includes", "keys", "toReversed", "toSorted",
            "toSpliced", "values");

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
    private final JsObject temporalDurationProto;
    private final JsObject temporalPlainTimeProto;
    private final JsObject temporalPlainDateProto;
    private final JsObject temporalInstantProto;
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
    private final JsNativeFunction throwTypeError;
    private final JsObject regexpStringIteratorProto;
    private final JsObject arrayIteratorProto;
    private final JsObject stringIteratorProto;
    private final JsObject mapIteratorProto;
    private final JsObject setIteratorProto;
    private final JsObject generatorFunctionProto;
    private final JsObject asyncGeneratorFunctionProto;
    private final JsObject asyncFunctionProto;
    private final Map<String, JsNativeFunction> functionKindCtors = new LinkedHashMap<>();
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
                (receiver, name) -> GeneratorBuiltins.getMethod(requireGenerator(receiver, name), name, objectProto));
        asyncIteratorProto = prototypeOf(GeneratorBuiltins.PROTO_NAMES, "AsyncGenerator.prototype",
                (receiver, name) -> asyncGeneratorMethod(receiver, name, driver, eventLoop));
        functionProto = prototypeOf(FunctionProtoBuiltins.NAMES, "Function.prototype", (receiver,
                name) -> FunctionProtoBuiltins.getMethod(requireCallable(receiver, name), name, invoker, ops));
        installFunctionHasInstance(functionProto);
        // Function.prototype is itself a (no-op) callable object per spec, with its own "length"/
        // "name" (immediately adjacent in own-key order, per CreateBuiltinFunction's Set order) and
        // an [object Function] toString brand - installed as a real @@toStringTag rather than a
        // brand() case, since Function.prototype's runtime type is a plain JsObject, not JsFunction/
        // JsNativeFunction/JsClass.
        functionProto.defineValue("length", new JsNumber(0));
        functionProto.setFlags("length", new PropertyFlags(false, false, true));
        functionProto.defineValue("name", new JsString(""));
        functionProto.setFlags("name", new PropertyFlags(false, false, true));
        defineToStringTag(functionProto, "Function");
        arrayProto = arrayPrototype();
        stringProto = prototypeOf(StringBuiltins.NAMES, "String.prototype",
                (receiver, name) -> StringBuiltins.isGeneric(name)
                        ? StringBuiltins.genericMethod(name, ops, invoker)
                        : StringBuiltins.getMethod(requireString(receiver, name), name, invoker, ops));
        installStringPrimitiveMethods(stringProto);
        numberProto = prototypeOf(NumberBuiltins.NAMES, "Number.prototype",
                (receiver, name) -> NumberBuiltins.getMethod(requireNumber(receiver, name), name, ops));
        booleanProto = booleanPrototype();
        // The three wrapper prototypes are themselves ordinary objects carrying the corresponding
        // primitive slot, so `Number.prototype.toString()` reads 0 rather than rejecting the receiver.
        stringProto.setPrimitive(new JsString(""));
        numberProto.setPrimitive(new JsNumber(0));
        booleanProto.setPrimitive(JsBoolean.FALSE);
        bigintProto = prototypeOf(BigIntBuiltins.NAMES, "BigInt.prototype",
                (receiver, name) -> BigIntBuiltins.getMethod(requireBigInt(receiver, name), name, ops));
        symbolProto = prototypeOf(SymbolBuiltins.NAMES, "Symbol.prototype",
                (receiver, name) -> SymbolBuiltins.getMethod(requireSymbol(receiver, name), name));
        installSymbolAccessors(symbolProto);
        regexpProto = prototypeOf(RegexBuiltins.NAMES, "RegExp.prototype",
                (receiver, name) -> RegexBuiltins.getMethod(requireRegExp(receiver, name), name, ops));
        installRegExpSymbolMethods(regexpProto);
        installRegExpAccessors(regexpProto);
        mapProto = prototypeOf(MapBuiltins.NAMES, "Map.prototype",
                (receiver, name) -> MapBuiltins.getMethod(requireMap(receiver, name, false), name, invoker));
        installMapSizeAccessor(mapProto);
        weakMapProto = prototypeOf(MapBuiltins.WEAK_NAMES, "WeakMap.prototype",
                (receiver, name) -> MapBuiltins.getMethod(requireMap(receiver, name, true), name, invoker));
        setProto = prototypeOf(SetBuiltins.NAMES, "Set.prototype",
                (receiver, name) -> SetBuiltins.getMethod(requireSet(receiver, name, false), name, invoker, ops));
        installSetSizeAccessor(setProto);
        weakSetProto = prototypeOf(SetBuiltins.WEAK_NAMES, "WeakSet.prototype",
                (receiver, name) -> SetBuiltins.getMethod(requireSet(receiver, name, true), name, invoker, ops));
        dateProto = prototypeOf(DateBuiltins.NAMES, "Date.prototype",
                (receiver, name) -> DateBuiltins.isGeneric(name)
                        ? DateBuiltins.genericMethod(name, ops)
                        : DateBuiltins.getMethod(requireDate(receiver, name), name, ops));
        // Date.prototype[@@toPrimitive] is the one non-writable well-known-symbol method.
        installSymbolValue(dateProto, JsSymbol.TO_PRIMITIVE, DateBuiltins.symbolToPrimitive(ops),
                new PropertyFlags(false, false, true));
        temporalDurationProto = temporalDurationPrototype();
        temporalPlainTimeProto = prototypeOf(TemporalPlainTimeBuiltins.NAMES, "Temporal.PlainTime.prototype", (receiver,
                name) -> TemporalPlainTimeBuiltins.getMethod(requireTemporalPlainTime(receiver, name), name, ops));
        installTemporalPlainTimeAccessors(temporalPlainTimeProto);
        defineToStringTag(temporalPlainTimeProto, "Temporal.PlainTime");
        temporalPlainDateProto = prototypeOf(TemporalPlainDateBuiltins.NAMES, "Temporal.PlainDate.prototype", (receiver,
                name) -> TemporalPlainDateBuiltins.getMethod(requireTemporalPlainDate(receiver, name), name, ops));
        TemporalPlainDateBuiltins.installAccessors(temporalPlainDateProto);
        temporalInstantProto = prototypeOf(TemporalInstantBuiltins.NAMES, "Temporal.Instant.prototype", (receiver,
                name) -> TemporalInstantBuiltins.getMethod(requireTemporalInstant(receiver, name), name, ops));
        TemporalInstantBuiltins.installAccessors(temporalInstantProto);
        arrayBufferProto = prototypeOf(TypedArrayBuiltins.BUFFER_NAMES, "ArrayBuffer.prototype",
                (receiver, name) -> TypedArrayBuiltins.bufferMethod(requireBuffer(receiver, name), name, ops));
        dataViewProto = prototypeOf(TypedArrayBuiltins.VIEW_NAMES, "DataView.prototype",
                (receiver, name) -> TypedArrayBuiltins.dataViewMethod(requireView(receiver, name), name, ops));
        typedArrayProto = prototypeOf(TypedArrayBuiltins.NAMES, "TypedArray.prototype", (receiver,
                name) -> TypedArrayBuiltins.getMethod(requireTypedArray(receiver, name), name, invoker, ops));
        // Per spec, %TypedArray%.prototype.toString is the very same (fully generic) function object
        // as Array.prototype.toString, not an independently-built brand-checked wrapper.
        define(typedArrayProto, "toString", arrayProto.get("toString"));
        // Per spec, %TypedArray%.prototype[Symbol.iterator] is the very same function object as
        // %TypedArray%.prototype.values (not just an equivalent one).
        defineSymbol(typedArrayProto, JsSymbol.ITERATOR, typedArrayProto.get("values"));
        // Real accessor properties (not just the fast-dispatch special-casing MemberEvaluator does
        // for actual JsTypedArray receivers) so `TypedArray.prototype.length` etc, invoked with a
        // non-typed-array `this`, throws per spec instead of silently reading through as undefined.
        installTypedArrayGeometryAccessors(typedArrayProto);
        installTypedArrayToStringTag(typedArrayProto);
        installGeometryAccessors(arrayBufferProto, TypedArrayBuiltins.bufferAccessorNames(),
                (thisArg, name) -> TypedArrayBuiltins.bufferMethod(requireBuffer(thisArg, name), name));
        installGeometryAccessors(dataViewProto, TypedArrayBuiltins.viewAccessorNames(),
                (thisArg, name) -> TypedArrayBuiltins.dataViewMethod(requireView(thisArg, name), name));
        for (final var kind : JsTypedArray.Kind.values()) {
            final var proto = new JsObject();
            proto.setProto(typedArrayProto);
            TypedArrayBuiltins.defineBytesPerElement(proto.ownProperties(), kind);
            typedArrayProtos.put(kind, proto);
        }
        for (final var name : TypedArrayBuiltins.UINT8_NAMES) {
            define(typedArrayProtos.get(JsTypedArray.Kind.UINT8), name, wrapper(name, "Uint8Array.prototype",
                    (receiver, key) -> TypedArrayBuiltins.uint8Method(requireUint8(receiver, key), key, ops)));
        }
        disposableStackProto = prototypeOf(DisposableStackBuiltins.NAMES, "DisposableStack.prototype",
                (receiver, name) -> DisposableStackBuiltins.getMethod(requireStack(receiver, name), name, ops, invoker,
                        eventLoop, false));
        DisposableStackBuiltins.installAccessors(disposableStackProto, false);
        asyncDisposableStackProto = prototypeOf(DisposableStackBuiltins.ASYNC_NAMES, "AsyncDisposableStack.prototype",
                (receiver, name) -> "disposeAsync".equals(name)
                        ? disposeAsyncMethod(receiver, name, eventLoop)
                        : DisposableStackBuiltins.getMethod(requireStack(receiver, name), name, ops, invoker, eventLoop,
                                true));
        DisposableStackBuiltins.installAccessors(asyncDisposableStackProto, true);
        installObjectPrototype();
        installErrorPrototypes();
        installIteratorSymbols();
        installToStringTags();
        installArrayUnscopables();
        throwTypeError = makeThrowTypeError();
        installPoisonPill(functionProto, "caller");
        installPoisonPill(functionProto, "arguments");
        regexpStringIteratorProto = regexpStringIteratorPrototype();
        arrayIteratorProto = JsIterators.prototype("Array Iterator", objectProto);
        stringIteratorProto = JsIterators.prototype("String Iterator", objectProto);
        mapIteratorProto = JsIterators.prototype("Map Iterator", objectProto);
        setIteratorProto = JsIterators.prototype("Set Iterator", objectProto);
        generatorFunctionProto = functionKindPrototype("GeneratorFunction", iteratorProto);
        asyncGeneratorFunctionProto = functionKindPrototype("AsyncGeneratorFunction", asyncIteratorProto);
        asyncFunctionProto = functionKindPrototype("AsyncFunction", null);
    }

    // %GeneratorFunction.prototype% and friends: the [[Prototype]] a generator/async function object
    // gets instead of Function.prototype, carrying the `constructor` a script reaches
    // %GeneratorFunction% through (calling it throws — there is no runtime code generation). Per spec
    // %GeneratorFunction%/%AsyncGeneratorFunction%/%AsyncFunction% are themselves subclasses of
    // %Function% ([[Prototype]] = Function, not Function.prototype); the real Function constructor
    // doesn't exist yet this early in realm construction, so the own-[[Prototype]] link is deferred to
    // linkFunctionKindConstructors (same pattern as linkIteratorPrototypes below).
    private JsObject functionKindPrototype(String name, JsObject instancePrototype) {
        final var proto = new JsObject();
        proto.setProto(functionProto);
        final var ctor = new JsNativeFunction(name, (_, _) -> {
            throw new TypeErrorException(name + " is not supported: SimpleJS has no runtime code generation");
        });
        ctor.setLength(1);
        ctor.markConstructor();
        ctor.setPrototype(proto);
        functionKindCtors.put(name, ctor);
        define(proto, "constructor", ctor);
        proto.setFlags("constructor", new PropertyFlags(false, false, true));
        if (instancePrototype != null) {
            define(proto, "prototype", instancePrototype);
            proto.setFlags("prototype", new PropertyFlags(false, false, true));
            // The back-link spec names %GeneratorPrototype%.constructor / %AsyncGeneratorPrototype%.
            // constructor: the instance prototype's own constructor is this function-kind prototype
            // object, not the generator/async-generator instances that happen to link through it.
            define(instancePrototype, "constructor", proto);
            instancePrototype.setFlags("constructor", new PropertyFlags(false, false, true));
        }
        defineToStringTag(proto, name);
        return proto;
    }

    // GlobalScope hands the real %Function% constructor back here once it exists (mirrors
    // linkIteratorPrototypes: both close a circular-construction gap in the realm bootstrap).
    public void linkFunctionKindConstructors(JsValue functionConstructor) {
        for (final var ctor : functionKindCtors.values()) {
            ctor.setOwnProto(functionConstructor);
        }
    }

    // %ThrowTypeError%: one anonymous, frozen, non-extensible function per realm, shared by every
    // poison-pill accessor so `Object.getOwnPropertyDescriptor(args, 'callee').get === ...caller.get`.
    private static JsNativeFunction makeThrowTypeError() {
        final var fn = new JsNativeFunction("", (_, _) -> {
            throw new TypeErrorException(
                    "'caller', 'callee', and 'arguments' properties may not be accessed on strict mode functions");
        });
        fn.setLength(0);
        final var table = fn.ownProperties();
        table.defineValue("length", new JsNumber(0));
        table.setFlags("length", new PropertyFlags(false, false, false));
        table.defineValue("name", new JsString(""));
        table.setFlags("name", new PropertyFlags(false, false, false));
        table.preventExtensions();
        return fn;
    }

    private void installPoisonPill(JsObject target, String key) {
        target.defineAccessor(key, throwTypeError, throwTypeError);
        target.setFlags(key, new PropertyFlags(false, false, true));
    }

    public void poison(JsValue target, String key) {
        final var table = target.ownProperties();
        if (table != null) {
            table.defineAccessor(key, throwTypeError, throwTypeError);
            table.setFlags(key, new PropertyFlags(false, false, true));
        }
    }

    // %RegExpStringIteratorPrototype%: the object String.prototype.matchAll's result inherits from.
    private JsObject regexpStringIteratorPrototype() {
        final var proto = new JsObject();
        proto.setProto(objectProto);
        final var next = new JsNativeFunction("next", (thisArg, _) -> RegexBuiltins.stringIteratorNext(thisArg, ops));
        next.setLength(0);
        define(proto, "next", next);
        defineToStringTag(proto, "RegExp String Iterator");
        return proto;
    }

    // %IteratorPrototype% / %AsyncIteratorPrototype% belong to the Iterator/AsyncIterator globals, so
    // GlobalScope hands them back here once both halves of the realm exist.
    public void linkIteratorPrototypes(JsValue iteratorPrototype, JsValue asyncIteratorPrototype) {
        if (iteratorPrototype != null) {
            iteratorPrototype.setProto(objectProto);
            iteratorProto.setProto(iteratorPrototype);
            regexpStringIteratorProto.setProto(iteratorPrototype);
            arrayIteratorProto.setProto(iteratorPrototype);
            stringIteratorProto.setProto(iteratorPrototype);
            mapIteratorProto.setProto(iteratorPrototype);
            setIteratorProto.setProto(iteratorPrototype);
        }
        if (asyncIteratorPrototype != null) {
            asyncIteratorPrototype.setProto(objectProto);
            asyncIteratorProto.setProto(asyncIteratorPrototype);
        }
    }

    private void installToStringTags() {
        defineToStringTag(iteratorProto, "Generator");
        defineToStringTag(mapProto, "Map");
        defineToStringTag(weakMapProto, "WeakMap");
        defineToStringTag(setProto, "Set");
        defineToStringTag(weakSetProto, "WeakSet");
        defineToStringTag(bigintProto, "BigInt");
        defineToStringTag(arrayBufferProto, "ArrayBuffer");
        defineToStringTag(dataViewProto, "DataView");
        defineToStringTag(disposableStackProto, "DisposableStack");
        defineToStringTag(asyncDisposableStackProto, "AsyncDisposableStack");
        defineToStringTag(temporalDurationProto, "Temporal.Duration");
        defineToStringTag(temporalPlainDateProto, "Temporal.PlainDate");
        defineToStringTag(temporalInstantProto, "Temporal.Instant");
        // %TypedArray%.prototype's tag is an accessor returning the *concrete* view's name, so
        // `Object.prototype.toString.call(new Int8Array())` reports Int8Array rather than a shared tag.
        final var getter = new JsNativeFunction("get [Symbol.toStringTag]", (thisArg, _) -> typedArrayTag(thisArg));
        getter.setLength(0);
        typedArrayProto.defineSymbolAccessor(JsSymbol.TO_STRING_TAG, getter, null);
        typedArrayProto.setSymbolFlags(JsSymbol.TO_STRING_TAG, new PropertyFlags(false, false, true));
    }

    private static JsValue typedArrayTag(JsValue receiver) {
        final var target = receiver instanceof JsTypedArray ? receiver : unwrap(receiver);
        return target instanceof JsTypedArray typed ? new JsString(typed.kind().ctorName()) : JsUndefined.getInstance();
    }

    private void installArrayUnscopables() {
        final var unscopables = new JsObject();
        unscopables.setProto(null);
        for (final var name : UNSCOPABLE_ARRAY_METHODS) {
            unscopables.set(name, JsBoolean.TRUE);
        }
        arrayProto.setSymbol(JsSymbol.UNSCOPABLES, unscopables);
        arrayProto.setSymbolFlags(JsSymbol.UNSCOPABLES, new PropertyFlags(false, false, true));
    }

    // Per spec these are the very same function objects as the named methods they alias, so a script
    // can compare, replace or delete them and every iteration site has to observe the change.
    private void installIteratorSymbols() {
        defineSymbol(arrayProto, JsSymbol.ITERATOR, arrayProto.get("values"));
        defineSymbol(mapProto, JsSymbol.ITERATOR, mapProto.get("entries"));
        defineSymbol(setProto, JsSymbol.ITERATOR, setProto.get("values"));
        defineSymbol(stringProto, JsSymbol.ITERATOR,
                new JsNativeFunction("[Symbol.iterator]", (thisArg,
                        _) -> JsIterators.linkPrototype(JsIterators.of(InterpreterUtils
                                .stringCodePoints(JsCoercion.toStr(requireString(thisArg, "[Symbol.iterator]"), ops))
                                .iterator()), stringIteratorProto)));
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
        ObjectProtoBuiltins.installProtoAccessor(objectProto, ops, this);
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

    // Error.prototype.toString is specified over Get(O, "name")/Get(O, "message"), so it walks the
    // prototype chain and observes an accessor, and an empty name yields just the message.
    private String errorText(JsObject error) {
        final var name = errorField(error, "name", "Error");
        final var message = errorField(error, "message", "");
        if (name.isEmpty()) {
            return message;
        }
        return message.isEmpty() ? name : name + ": " + message;
    }

    private String errorField(JsObject error, String key, String fallback) {
        final var value = ops == null ? error.get(key) : ops.getMember(error, new JsString(key));
        if (value == null || value instanceof JsUndefined) {
            return fallback;
        }
        return JsCoercion.toStr(value, ops);
    }

    // Unlike every other String.prototype method these two are brand-checked rather than generic:
    // ToString(this) would happily turn a number receiver into a string instead of throwing.
    private void installStringPrimitiveMethods(JsObject proto) {
        for (final var name : List.of("toString", "valueOf")) {
            final var method = new JsNativeFunction(name, (thisArg, _) -> requireStringData(thisArg, name));
            method.setLength(0);
            define(proto, name, method);
        }
    }

    private JsString requireStringData(JsValue receiver, String method) {
        if (receiver instanceof JsString string) {
            return string;
        }
        if (unwrap(receiver) instanceof JsString wrapped) {
            return wrapped;
        }
        throw incompatible("String.prototype." + method, receiver);
    }

    private JsObject booleanPrototype() {
        final var proto = new JsObject();
        define(proto, "toString", new JsNativeFunction("toString",
                (thisArg, _) -> new JsString(JsCoercion.toStr(requireBoolean(thisArg, "toString")))));
        define(proto, "valueOf", new JsNativeFunction("valueOf", (thisArg, _) -> requireBoolean(thisArg, "valueOf")));
        proto.setProto(objectProto);
        return proto;
    }

    private JsObject arrayPrototype() {
        final var proto = new JsObject();
        for (final var name : ArrayBuiltins.NAMES) {
            final var method = new JsNativeFunction(name, (thisArg, args) -> callArrayMethod(thisArg, name, args));
            method.setLength(BuiltinLengths.lengthOf("Array.prototype", name));
            define(proto, name, method);
        }
        // %Array.prototype% has a real own "length" per spec (22.1.3): initial value 0, attributes
        // {[[Writable]]: true, [[Enumerable]]: false, [[Configurable]]: false}. It is an ordinary data
        // property on the plain JsObject %Array.prototype% is built from, not a real JsArray - but
        // MemberEvaluator.setObjectMember special-cases an index write on exactly this object to bump
        // it the way a genuine Array exotic object's [[DefineOwnProperty]] would (see the comment
        // there and ArrayBuiltinsTest.test_array_prototype_is_a_genuine_array_exotic_object), so the
        // exotic behaviour is present without converting this object's Java type to JsArray.
        // MemberEvaluator.getObjectMember/setObjectMember separately give a `class A extends Array`
        // instance's wrapped-primitive length priority over this own property, so it is safe for a
        // subclass instance's real length to never be shadowed by it (see the comment there and
        // ArrayBuiltinsTest.test_is_array_covers_proxies_and_the_intrinsic_prototype).
        proto.defineValue("length", new JsNumber(0));
        proto.setFlags("length", new PropertyFlags(true, false, false));
        proto.setProto(objectProto);
        return proto;
    }

    // Every Array.prototype method is generic: ToObject(this value) is the only receiver check, and
    // ArrayBuiltins then reads and writes it lazily through the member seam.
    private JsValue callArrayMethod(JsValue thisArg, String name, List<JsValue> args) {
        if (thisArg instanceof JsNull || thisArg instanceof JsUndefined) {
            throw incompatible("Array.prototype." + name, thisArg);
        }
        final var receiver = toObject(thisArg);
        final var resolved = ArrayBuiltins.getMethod(receiver, name, invoker, ops);
        if (resolved == null) {
            throw incompatible("Array.prototype." + name, thisArg);
        }
        return JsIterators.linkPrototype(invoker.call(resolved, receiver, args),
                builtinIteratorProto("Array.prototype", name));
    }

    // ToObject: a primitive receiver is boxed so the callback's third argument is an object and the
    // wrapper's prototype (which a test may extend) takes part in the index lookups.
    public JsValue toObject(JsValue value) {
        if (InterpreterUtils.isObjectLike(value)) {
            return value;
        }
        if (value instanceof JsNull || value instanceof JsUndefined) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        return wrapPrimitive(value, protoFor(value));
    }

    // The single construction path for a primitive wrapper: Object(x), new String/Number/Boolean and
    // ToObject all land here, so a wrapper always carries its primitive and an intrinsic prototype.
    public JsObject wrapPrimitive(JsValue primitive, JsValue proto) {
        final var wrapper = new JsObject();
        wrapper.setProto(proto);
        wrapper.setPrimitive(primitive);
        return wrapper;
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
            return JsIterators.linkPrototype(invoker.call(method, thisArg, args), builtinIteratorProto(label, name));
        });
        wrapped.setLength(BuiltinLengths.lengthOf(label, name));
        return wrapped;
    }

    // The keys/values/entries builtins hand back a bare JsIterators instance, which only becomes a
    // %MapIteratorPrototype% (etc) instance here: this dispatch seam is the one place that knows both
    // which builtin ran and which realm it ran in.
    private JsObject builtinIteratorProto(String label, String name) {
        if (!"keys".equals(name) && !"values".equals(name) && !"entries".equals(name)) {
            return null;
        }
        return switch (label) {
            case "Map.prototype", "WeakMap.prototype" -> mapIteratorProto;
            case "Set.prototype", "WeakSet.prototype" -> setIteratorProto;
            case "Array.prototype", "TypedArray.prototype" -> arrayIteratorProto;
            default -> null;
        };
    }

    private static void define(JsObject target, String key, JsValue value) {
        defineHidden(target, key, value);
    }

    static void defineHidden(JsObject target, String key, JsValue value) {
        target.defineValue(key, value);
        target.setFlags(key, HIDDEN);
    }

    // The four install helpers every builtin surface goes through, so a spec attribute set is chosen
    // once per shape instead of at each call site: a method/symbol-keyed method is
    // {w:true, e:false, c:true}, a @@toStringTag is {w:false, e:false, c:true}.
    public static void installMethod(JsObject target, String key, JsValue fn) {
        defineHidden(target, key, fn);
    }

    public static void installSymbolMethod(JsObject target, JsSymbol key, JsValue fn) {
        target.setSymbol(key, fn);
        target.setSymbolFlags(key, HIDDEN);
    }

    public static void installSymbolValue(JsObject target, JsSymbol key, JsValue value, PropertyFlags flags) {
        target.ownProperties().defineSymbolValue(key, value);
        target.setSymbolFlags(key, flags);
    }

    public static void installTag(JsObject target, String tag) {
        defineToStringTag(target, tag);
    }

    static void defineFrozen(JsObject target, String key, JsValue value) {
        target.defineValue(key, value);
        target.setFlags(key, new PropertyFlags(false, false, false));
    }

    static void defineNamespaceTag(JsObject target, String tag) {
        defineToStringTag(target, tag);
    }

    // Interpreter.getPrototypeOf's default branch special-cases JsClass/JsGenerator/
    // JsAsyncGenerator's real [[Prototype]] slot before falling back to this method, but never
    // gained matching cases for JsArray/JsTypedArray (both of which do carry a real, settable slot -
    // see their own getProto()/setProto() overrides), so Object.getPrototypeOf(array) kept reporting
    // the intrinsic Array.prototype default even after Object.setPrototypeOf(array, x) changed the
    // array's own slot. This check is deliberately scoped to just those two types rather than every
    // JsObject: getObjectMember's plain-object fallback calls protoFor(object) specifically *after*
    // walking object.getProto() has already failed to find the member (the Wave 3 fix for the
    // Object.create(null) ambiguity - see its own call site), so re-consulting value.getProto() here
    // for a general JsObject would re-walk that same already-exhausted chain instead of falling
    // through to the real intrinsic default, reintroducing the "one link short of Object.prototype"
    // regression that fix closed.
    public JsObject protoFor(JsValue value) {
        if ((value instanceof JsArray || value instanceof JsTypedArray) && value.getProto() instanceof JsObject own) {
            return own;
        }
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
            case JsTemporalDuration ignored -> temporalDurationProto;
            case JsTemporalPlainTime ignored -> temporalPlainTimeProto;
            case JsTemporalPlainDate ignored -> temporalPlainDateProto;
            case JsTemporalInstant ignored -> temporalInstantProto;
            case JsPromise ignored -> promiseProto;
            case JsGenerator ignored -> iteratorProto;
            case JsAsyncGenerator ignored -> asyncIteratorProto;
            case JsArrayBuffer ignored -> arrayBufferProto;
            case JsDataView ignored -> dataViewProto;
            case JsTypedArray typed -> typedArrayProtos.get(typed.kind());
            case JsFunction function -> functionKindProtoFor(function);
            case JsNativeFunction ignored -> functionProto;
            default -> objectProto;
        };
    }

    private JsObject functionKindProtoFor(JsFunction function) {
        if (function.isGenerator()) {
            return function.isAsync() ? asyncGeneratorFunctionProto : generatorFunctionProto;
        }
        return function.isAsync() ? asyncFunctionProto : functionProto;
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

    public JsObject temporalDurationProto() {
        return temporalDurationProto;
    }

    public JsObject temporalPlainTimeProto() {
        return temporalPlainTimeProto;
    }

    public JsObject temporalPlainDateProto() {
        return temporalPlainDateProto;
    }

    public JsObject temporalInstantProto() {
        return temporalInstantProto;
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
                    (thisArg, _) -> RegexBuiltins.protoAccessor(regExpReceiver(thisArg), name, proto, ops));
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
                argStr(args), args.size() > 1 ? args.get(1) : JsUndefined.getInstance(), proto, ops));
        split.setLength(2);
        proto.setSymbol(JsSymbol.SPLIT, split);
        final var matchAll = new JsNativeFunction("[Symbol.matchAll]", (thisArg, args) -> RegexBuiltins
                .symbolMatchAll(thisArg, argStr(args), regexpStringIteratorProto, proto, ops));
        matchAll.setLength(1);
        proto.setSymbol(JsSymbol.MATCH_ALL, matchAll);
        for (final var symbol : List.of(JsSymbol.MATCH, JsSymbol.SEARCH, JsSymbol.REPLACE, JsSymbol.SPLIT,
                JsSymbol.MATCH_ALL)) {
            proto.setSymbolFlags(symbol, HIDDEN);
        }
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

    // AsyncGenerator.prototype.{next,return,throw} create their PromiseCapability before the brand
    // check per spec (IfAbruptRejectPromise), so a receiver that fails the check rejects the returned
    // promise instead of throwing synchronously.
    private JsValue asyncGeneratorMethod(JsValue receiver, String name, GeneratorBuiltins.AsyncDriver driver,
            EventLoop eventLoop) {
        try {
            return GeneratorBuiltins.getAsyncMethod(requireAsyncGenerator(receiver, name), name, driver);
        } catch (TypeErrorException e) {
            return rejectedMethod(name, eventLoop, e);
        }
    }

    // AsyncDisposableStack.prototype.disposeAsync has the same create-capability-before-brand-check
    // shape: an ordinary object, a DisposableStack, or a disposed stack all reject rather than throw.
    private JsValue disposeAsyncMethod(JsValue receiver, String name, EventLoop eventLoop) {
        try {
            final var stack = requireStack(receiver, name);
            final var method = DisposableStackBuiltins.getMethod(stack, name, ops, invoker, eventLoop, true);
            if (method != null) {
                return method;
            }
            throw incompatible("AsyncDisposableStack.prototype." + name, receiver);
        } catch (TypeErrorException e) {
            return rejectedMethod(name, eventLoop, e);
        }
    }

    private JsNativeFunction rejectedMethod(String name, EventLoop eventLoop, RuntimeException error) {
        return new JsNativeFunction(name, (_, _) -> {
            final var promise = new JsPromise(eventLoop);
            promise.reject(InterpreterUtils.toErrorValue(error, this));
            return promise;
        });
    }

    // `size` is an accessor on the prototype, not a data method: it has to be reachable off a
    // foreign/subclassed receiver by walking the prototype chain (docs' "builtin subclassing" note -
    // a subclass instance is a plain JsObject wrapping the real JsMap in its primitive slot), so the
    // brand check goes through requireMap's own unwrap fallback rather than a raw `instanceof`.
    private void installMapSizeAccessor(JsObject proto) {
        final var getter = new JsNativeFunction("get size",
                (thisArg, _) -> new JsNumber(requireMap(thisArg, "size", false).size()));
        getter.setLength(0);
        proto.defineAccessor("size", getter, null);
        proto.setFlags("size", new PropertyFlags(true, false, true));
    }

    // Same shape as installMapSizeAccessor; `keys` is not a method of its own, it is the very same
    // function object as `values`, which a script can compare.
    private void installSetSizeAccessor(JsObject proto) {
        final var getter = new JsNativeFunction("get size",
                (thisArg, _) -> new JsNumber(requireSet(thisArg, "size", false).size()));
        getter.setLength(0);
        proto.defineAccessor("size", getter, null);
        proto.setFlags("size", new PropertyFlags(true, false, true));
        final var values = proto.get("values");
        if (values != null) {
            installMethod(proto, "keys", values);
        }
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

    // Temporal.Duration.prototype mixes plain methods (dispatched through the generic wrapper, same
    // as every other prototype here) with per-instance field accessors (years/months/.../sign/blank),
    // which are getters rather than methods - so this prototype is built by hand instead of via the
    // single-list prototypeOf helper every method-only prototype uses.
    private JsObject temporalDurationPrototype() {
        final var proto = new JsObject();
        for (final var name : TemporalDurationBuiltins.ACCESSOR_NAMES) {
            final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> TemporalDurationBuiltins
                    .fieldAccessor(requireTemporalDuration(thisArg, name), name));
            getter.setLength(0);
            proto.defineAccessor(name, getter, null);
            proto.setFlags(name, HIDDEN);
        }
        for (final var name : TemporalDurationBuiltins.METHOD_NAMES) {
            define(proto, name, wrapper(name, "Temporal.Duration.prototype", (receiver, key) -> TemporalDurationBuiltins
                    .getMethod(requireTemporalDuration(receiver, key), key, ops)));
        }
        proto.setProto(objectProto);
        return proto;
    }

    private JsTemporalDuration requireTemporalDuration(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalDuration duration) {
            return duration;
        }
        if (unwrap(receiver) instanceof JsTemporalDuration wrapped) {
            return wrapped;
        }
        throw incompatible("Temporal.Duration.prototype." + method, receiver);
    }

    // Real accessor properties (RegExp/TypedArray-geometry shaped), not method-shaped: `hour` etc.
    // are getters on the shared prototype, not entries in TemporalPlainTimeBuiltins.NAMES.
    private void installTemporalPlainTimeAccessors(JsObject proto) {
        for (final var name : TemporalPlainTimeBuiltins.FIELD_ACCESSORS) {
            final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> TemporalPlainTimeBuiltins
                    .fieldAccessor(requireTemporalPlainTime(thisArg, name), name));
            proto.defineAccessor(name, getter, null);
            proto.setFlags(name, HIDDEN);
        }
    }

    private JsTemporalPlainTime requireTemporalPlainTime(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalPlainTime time) {
            return time;
        }
        if (unwrap(receiver) instanceof JsTemporalPlainTime wrapped) {
            return wrapped;
        }
        throw incompatible("Temporal.PlainTime.prototype." + method, receiver);
    }

    private JsTemporalPlainDate requireTemporalPlainDate(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalPlainDate date) {
            return date;
        }
        if (unwrap(receiver) instanceof JsTemporalPlainDate wrapped) {
            return wrapped;
        }
        throw incompatible("Temporal.PlainDate.prototype." + method, receiver);
    }

    private JsTemporalInstant requireTemporalInstant(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalInstant instant) {
            return instant;
        }
        if (unwrap(receiver) instanceof JsTemporalInstant wrapped) {
            return wrapped;
        }
        throw incompatible("Temporal.Instant.prototype." + method, receiver);
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

    // Unlike every other @@toStringTag this one is a getter that answers undefined rather than
    // throwing for a foreign receiver, so Object.prototype.toString still reports [object Object].
    private void installTypedArrayToStringTag(JsObject proto) {
        final var getter = new JsNativeFunction("get [Symbol.toStringTag]", (thisArg, _) -> {
            final var typed = thisArg instanceof JsTypedArray direct ? direct : null;
            return typed == null ? JsUndefined.getInstance() : new JsString(typed.kind().ctorName());
        });
        getter.setLength(0);
        proto.defineSymbolAccessor(JsSymbol.TO_STRING_TAG, getter, null);
        proto.setSymbolFlags(JsSymbol.TO_STRING_TAG, HIDDEN);
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
        if (receiver instanceof JsFunction || receiver instanceof JsNativeFunction || receiver instanceof JsClass
                || (receiver instanceof JsProxy proxy && proxy.isCallable())) {
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
