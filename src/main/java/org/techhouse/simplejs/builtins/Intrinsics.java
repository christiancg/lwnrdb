package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireBigInt;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireBuffer;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireCallable;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireDate;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireGenerator;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireMap;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireNumber;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireRegExp;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireSet;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireStack;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireSymbol;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTypedArray;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireUint8;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireView;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.unwrap;
import static org.techhouse.simplejs.values.JsObject.PropertyFlags.HIDDEN;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands;
import org.techhouse.simplejs.builtins.intrinsics.IntrinsicsCoercion;
import org.techhouse.simplejs.builtins.intrinsics.IntrinsicsInstallers;
import org.techhouse.simplejs.builtins.intrinsics.IntrinsicsLayout;
import org.techhouse.simplejs.builtins.intrinsics.IntrinsicsPrototypes;
import org.techhouse.simplejs.builtins.string.StringRegExpDelegation;
import org.techhouse.simplejs.builtins.typedarray.ArrayBufferBuiltins;
import org.techhouse.simplejs.builtins.typedarray.DataViewBuiltins;
import org.techhouse.simplejs.builtins.typedarray.TypedArrayIteration;
import org.techhouse.simplejs.builtins.typedarray.Uint8Base64;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsDbDateTime;
import org.techhouse.simplejs.values.JsDbTime;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsGeo;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.JsVector;

public final class Intrinsics {
    @FunctionalInterface
    public interface MethodResolver {
        JsValue resolve(JsValue receiver, String name);
    }

    public static final List<String> ERROR_NAMES = List.of("Error", "TypeError", "RangeError", "SyntaxError",
            "URIError", "ReferenceError", "EvalError", "SuppressedError", "AggregateError");
    public static final List<String> UNSCOPABLE_ARRAY_METHODS = List.of("at", "copyWithin", "entries", "fill", "find",
            "findIndex", "findLast", "findLastIndex", "flat", "flatMap", "includes", "keys", "toReversed", "toSorted",
            "toSpliced", "values");

    public final Invoker invoker;
    public final InterpreterOps ops;
    private final Supplier<List<String>> stack;

    public final JsObject objectProto = new JsObject();
    public final JsObject functionProto;
    public final JsObject arrayProto;
    public final JsObject stringProto;
    public final JsObject numberProto;
    public final JsObject booleanProto;
    public final JsObject bigintProto;
    public final JsObject symbolProto;
    public final JsObject regexpProto;
    public final JsObject mapProto;
    public final JsObject weakMapProto;
    public final JsObject setProto;
    public final JsObject weakSetProto;
    public final JsObject dateProto;
    public final JsObject temporalDurationProto;
    public final JsObject temporalPlainTimeProto;
    public final JsObject temporalPlainDateProto;
    public final JsObject temporalInstantProto;
    public final JsObject temporalPlainYearMonthProto;
    public final JsObject temporalPlainMonthDayProto;
    public final JsObject temporalPlainDateTimeProto;
    public final JsObject temporalZonedDateTimeProto;
    public final JsObject geoProto;
    public final JsObject vectorProto;
    public final JsObject dbDateTimeProto;
    public final JsObject dbTimeProto;
    public final JsObject promiseProto;
    public final JsObject iteratorProto;
    public final JsObject asyncIteratorProto;
    public final JsObject arrayBufferProto;
    public final JsObject dataViewProto;
    private final Map<JsTypedArray.Kind, JsObject> typedArrayProtos = new EnumMap<>(JsTypedArray.Kind.class);
    public final JsObject typedArrayProto;
    public final JsObject disposableStackProto;
    public final JsObject asyncDisposableStackProto;
    public final JsObject errorProto = new JsObject();
    public final JsNativeFunction throwTypeError;
    public final JsObject regexpStringIteratorProto;
    public final JsObject arrayIteratorProto;
    public final JsObject stringIteratorProto;
    public final JsObject mapIteratorProto;
    public final JsObject setIteratorProto;
    public final JsObject dbCursorProto;
    private final JsObject generatorFunctionProto;
    private final JsObject asyncGeneratorFunctionProto;
    private final JsObject asyncFunctionProto;
    public final Map<String, JsNativeFunction> functionKindCtors = new LinkedHashMap<>();
    private JsValue defaultHasInstance;
    private JsValue defaultArrayIterator;
    private JsValue defaultStringIterator;
    private JsValue defaultTypedArrayIterator;
    private JsValue originalArrayToString;
    public final Map<String, JsObject> errorProtos = new LinkedHashMap<>();

    public final IntrinsicsInstallers installers = new IntrinsicsInstallers(this);

    public JsValue defaultHasInstance() {
        functionProto.ensureSetUp();
        return defaultHasInstance;
    }

    public JsValue defaultArrayIterator() {
        arrayProto.ensureSetUp();
        return defaultArrayIterator;
    }

    public JsValue defaultStringIterator() {
        stringProto.ensureSetUp();
        return defaultStringIterator;
    }

    public JsValue defaultTypedArrayIterator() {
        typedArrayProto.ensureSetUp();
        return defaultTypedArrayIterator;
    }

    private JsValue originalArrayToString() {
        arrayProto.ensureSetUp();
        return originalArrayToString;
    }

    public void rememberDefaultHasInstance(JsValue value) {
        defaultHasInstance = value;
    }

    public void rememberDefaultArrayIterator(JsValue value) {
        defaultArrayIterator = value;
    }

    public void rememberDefaultStringIterator(JsValue value) {
        defaultStringIterator = value;
    }

    public void rememberArrayToString(JsValue value) {
        originalArrayToString = value;
    }

    public boolean isDefaultHasInstance(JsValue candidate) {
        return installers.isDefaultHasInstance(candidate);
    }

    public boolean isDefaultIterator(JsValue target, JsValue candidate) {
        return installers.isDefaultIterator(target, candidate);
    }

    public final IntrinsicsPrototypes prototypes = new IntrinsicsPrototypes(this);
    public final IntrinsicsCoercion coercion = new IntrinsicsCoercion(this);

    public Intrinsics(Invoker invoker, InterpreterOps ops, EventLoop eventLoop, GeneratorBuiltins.AsyncDriver driver) {
        this(invoker, ops, eventLoop, driver, List::of);
    }

    public Intrinsics(Invoker invoker, InterpreterOps ops, EventLoop eventLoop, GeneratorBuiltins.AsyncDriver driver,
            Supplier<List<String>> stack) {
        this.invoker = invoker;
        this.ops = ops;
        this.stack = stack;
        promiseProto = coercion.prototypeOf(PromiseBuiltins.PROTO_NAMES, "Promise.prototype",
                (receiver, name) -> PromiseBuiltins.getMethod(receiver, name, eventLoop, invoker, this));
        iteratorProto = coercion.prototypeOf(GeneratorBuiltins.PROTO_NAMES, "Generator.prototype",
                (receiver, name) -> GeneratorBuiltins.getMethod(requireGenerator(receiver, name), name, objectProto));
        asyncIteratorProto = coercion.prototypeOf(GeneratorBuiltins.PROTO_NAMES, "AsyncGenerator.prototype",
                (receiver, name) -> installers.asyncGeneratorMethod(receiver, name, driver, eventLoop));
        functionProto = coercion.prototypeOf(FunctionProtoBuiltins.NAMES, "Function.prototype", (receiver,
                name) -> FunctionProtoBuiltins.getMethod(requireCallable(receiver, name), name, invoker, ops));
        functionProto.deferSetup(proto -> {
            installers.installFunctionHasInstance(proto);
            proto.defineValue("length", new JsNumber(0));
            proto.setFlags("length", PropertyFlags.TAG);
            proto.defineValue("name", new JsString(""));
            proto.setFlags("name", PropertyFlags.TAG);
            IntrinsicsInstallers.defineToStringTag(proto, "Function");
        });
        arrayProto = prototypes.arrayPrototype();
        stringProto = coercion.prototypeOf(StringBuiltins.NAMES, "String.prototype",
                (receiver, name) -> StringRegExpDelegation.isGeneric(name)
                        ? StringRegExpDelegation.genericMethod(name, ops, invoker)
                        : StringBuiltins.getMethod(requireStringValue(receiver, name), name, invoker, ops));
        stringProto.deferSetup(prototypes::installStringPrimitiveMethods);
        numberProto = coercion.prototypeOf(NumberBuiltins.NAMES, "Number.prototype",
                (receiver, name) -> NumberBuiltins.getMethod(requireNumber(receiver, name), name, ops));
        booleanProto = prototypes.booleanPrototype();
        stringProto.deferSetup(proto -> proto.setPrimitive(new JsString("")));
        numberProto.setPrimitive(new JsNumber(0));
        booleanProto.setPrimitive(JsBoolean.FALSE);
        bigintProto = coercion.prototypeOf(BigIntBuiltins.NAMES, "BigInt.prototype",
                (receiver, name) -> BigIntBuiltins.getMethod(requireBigInt(receiver, name), name, ops));
        symbolProto = coercion.prototypeOf(SymbolBuiltins.NAMES, "Symbol.prototype",
                (receiver, name) -> SymbolBuiltins.getMethod(requireSymbol(receiver, name), name));
        symbolProto.deferSetup(installers::installSymbolAccessors);
        regexpProto = coercion.prototypeOf(RegexBuiltins.NAMES, "RegExp.prototype",
                (receiver, name) -> RegexBuiltins.getMethod(requireRegExp(receiver, name), name, ops));
        regexpProto.deferSetup(proto -> {
            installers.installRegExpSymbolMethods(proto);
            installers.installRegExpAccessors(proto);
        });
        mapProto = coercion.prototypeOf(MapBuiltins.NAMES, "Map.prototype",
                (receiver, name) -> MapBuiltins.getMethod(requireMap(receiver, name, false), name, invoker));
        mapProto.deferSetup(installers::installMapSizeAccessor);
        weakMapProto = coercion.prototypeOf(MapBuiltins.WEAK_NAMES, "WeakMap.prototype",
                (receiver, name) -> MapBuiltins.getMethod(requireMap(receiver, name, true), name, invoker));
        setProto = coercion.prototypeOf(SetBuiltins.NAMES, "Set.prototype",
                (receiver, name) -> SetBuiltins.getMethod(requireSet(receiver, name, false), name, invoker, ops));
        setProto.deferSetup(installers::installSetSizeAccessor);
        weakSetProto = coercion.prototypeOf(SetBuiltins.WEAK_NAMES, "WeakSet.prototype",
                (receiver, name) -> SetBuiltins.getMethod(requireSet(receiver, name, true), name, invoker, ops));
        dateProto = coercion.prototypeOf(DateBuiltins.NAMES, "Date.prototype",
                (receiver, name) -> DateBuiltins.isGeneric(name)
                        ? DateBuiltins.genericMethod(name, ops)
                        : DateBuiltins.getMethod(requireDate(receiver, name), name, ops));
        dateProto.deferSetup(proto -> installToPrimitive(proto, DateBuiltins.symbolToPrimitive(ops)));
        final var layout = new IntrinsicsLayout(this);
        temporalDurationProto = layout.temporalDuration();
        temporalPlainTimeProto = layout.temporalPlainTime();
        temporalPlainDateProto = layout.temporalPlainDate();
        temporalInstantProto = layout.temporalInstant();
        temporalPlainYearMonthProto = layout.temporalPlainYearMonth();
        temporalPlainMonthDayProto = layout.temporalPlainMonthDay();
        temporalPlainDateTimeProto = layout.temporalPlainDateTime();
        temporalZonedDateTimeProto = layout.temporalZonedDateTime();
        geoProto = layout.geo();
        vectorProto = layout.vector();
        dbDateTimeProto = layout.dbDateTime();
        dbTimeProto = layout.dbTime();
        arrayBufferProto = coercion.prototypeOf(TypedArrayBuiltins.BUFFER_NAMES, "ArrayBuffer.prototype",
                (receiver, name) -> ArrayBufferBuiltins.bufferMethod(requireBuffer(receiver, name), name, ops));
        dataViewProto = coercion.prototypeOf(TypedArrayBuiltins.VIEW_NAMES, "DataView.prototype",
                (receiver, name) -> DataViewBuiltins.dataViewMethod(requireView(receiver, name), name, ops));
        typedArrayProto = coercion.prototypeOf(TypedArrayBuiltins.NAMES, "TypedArray.prototype", (receiver,
                name) -> TypedArrayIteration.getMethod(requireTypedArray(receiver, name), name, invoker, ops));
        typedArrayProto.deferSetup(proto -> {
            define(proto, "toString", originalArrayToString());
            installers.defineSymbol(proto, JsSymbol.ITERATOR, proto.get("values"));
            defaultTypedArrayIterator = proto.getSymbol(JsSymbol.ITERATOR);
            installers.installTypedArrayGeometryAccessors(proto);
            installers.installTypedArrayToStringTag(proto);
        });
        arrayBufferProto.deferSetup(
                proto -> installers.installGeometryAccessors(proto, ArrayBufferBuiltins.bufferAccessorNames(),
                        (thisArg, name) -> ArrayBufferBuiltins.bufferMethod(requireBuffer(thisArg, name), name)));
        dataViewProto
                .deferSetup(proto -> installers.installGeometryAccessors(proto, DataViewBuiltins.viewAccessorNames(),
                        (thisArg, name) -> DataViewBuiltins.dataViewMethod(requireView(thisArg, name), name)));
        for (final var kind : JsTypedArray.Kind.values()) {
            final var proto = new JsObject();
            proto.setProto(typedArrayProto);
            proto.deferSetup(target -> TypedArrayBuiltins.defineBytesPerElement(target.ownProperties(), kind));
            typedArrayProtos.put(kind, proto);
        }
        typedArrayProtos.get(JsTypedArray.Kind.UINT8).deferSetup(proto -> {
            for (final var name : TypedArrayBuiltins.UINT8_NAMES) {
                define(proto, name, coercion.wrapper(name, "Uint8Array.prototype",
                        (receiver, key) -> Uint8Base64.uint8Method(requireUint8(receiver, key), key, ops)));
            }
        });
        disposableStackProto = coercion.prototypeOf(DisposableStackBuiltins.NAMES, "DisposableStack.prototype",
                (receiver, name) -> DisposableStackBuiltins.getMethod(requireStack(receiver, name), name, this, invoker,
                        eventLoop, false));
        disposableStackProto.deferSetup(proto -> DisposableStackBuiltins.installAccessors(proto, false));
        asyncDisposableStackProto = coercion.prototypeOf(DisposableStackBuiltins.ASYNC_NAMES,
                "AsyncDisposableStack.prototype",
                (receiver, name) -> "disposeAsync".equals(name)
                        ? installers.disposeAsyncMethod(receiver, name, eventLoop)
                        : DisposableStackBuiltins.getMethod(requireStack(receiver, name), name, this, invoker,
                                eventLoop, true));
        asyncDisposableStackProto.deferSetup(proto -> DisposableStackBuiltins.installAccessors(proto, true));
        prototypes.installObjectPrototype();
        prototypes.installErrorPrototypes();
        installers.installIteratorSymbols();
        installers.installToStringTags();
        installers.installArrayUnscopables();
        throwTypeError = IntrinsicsPrototypes.makeThrowTypeError();
        functionProto.deferSetup(proto -> {
            prototypes.installPoisonPill(proto, "caller");
            prototypes.installPoisonPill(proto, "arguments");
        });
        regexpStringIteratorProto = prototypes.regexpStringIteratorPrototype();
        arrayIteratorProto = JsIterators.prototype("Array Iterator", objectProto);
        stringIteratorProto = JsIterators.prototype("String Iterator", objectProto);
        mapIteratorProto = JsIterators.prototype("Map Iterator", objectProto);
        setIteratorProto = JsIterators.prototype("Set Iterator", objectProto);
        dbCursorProto = JsIterators.prototype("Database Cursor", objectProto);
        generatorFunctionProto = prototypes.functionKindPrototype("GeneratorFunction", iteratorProto);
        asyncGeneratorFunctionProto = prototypes.functionKindPrototype("AsyncGeneratorFunction", asyncIteratorProto);
        asyncFunctionProto = prototypes.functionKindPrototype("AsyncFunction", null);
    }

    public static void define(JsObject target, String key, JsValue value) {
        defineHidden(target, key, value);
    }

    public static void defineHidden(JsObject target, String key, JsValue value) {
        target.defineValue(key, value);
        target.setFlags(key, HIDDEN);
    }

    public static void installMethod(JsObject target, String key, JsValue fn) {
        defineHidden(target, key, fn);
    }

    static void installSymbolMethod(JsObject target, JsSymbol key, JsValue fn) {
        target.setSymbol(key, fn);
        target.setSymbolFlags(key, HIDDEN);
    }

    static void installToPrimitive(JsObject target, JsValue value) {
        target.ownProperties().defineSymbolValue(JsSymbol.TO_PRIMITIVE, value);
        target.setSymbolFlags(JsSymbol.TO_PRIMITIVE, PropertyFlags.TAG);
    }

    static void installTag(JsObject target, String tag) {
        IntrinsicsInstallers.defineToStringTag(target, tag);
    }

    static void defineFrozen(JsObject target, String key, JsValue value) {
        target.defineValue(key, value);
        target.setFlags(key, new PropertyFlags(false, false, false));
    }

    static void defineNamespaceTag(JsObject target, String tag) {
        IntrinsicsInstallers.defineToStringTag(target, tag);
    }

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
            case JsTemporalPlainYearMonth ignored -> temporalPlainYearMonthProto;
            case JsTemporalPlainMonthDay ignored -> temporalPlainMonthDayProto;
            case JsTemporalPlainDateTime ignored -> temporalPlainDateTimeProto;
            case JsTemporalZonedDateTime ignored -> temporalZonedDateTimeProto;
            case JsGeo ignored -> geoProto;
            case JsVector ignored -> vectorProto;
            case JsDbDateTime ignored -> dbDateTimeProto;
            case JsDbTime ignored -> dbTimeProto;
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

    public JsObject functionKindProtoFor(JsFunction function) {
        if (function.isGenerator()) {
            return function.isAsync() ? asyncGeneratorFunctionProto : generatorFunctionProto;
        }
        return function.isAsync() ? asyncFunctionProto : functionProto;
    }

    public JsObject typedArrayProto(JsTypedArray.Kind kind) {
        return typedArrayProtos.get(kind);
    }

    public JsObject errorProto(String name) {
        return errorProtos.getOrDefault(name, errorProto);
    }

    public JsObject makeError(String name, String message) {
        return ErrorBuiltins.makeError(name, message, errorProto(name), captureStack());
    }

    public List<String> captureStack() {
        return stack.get();
    }

    public JsString requireStringValue(JsValue receiver, String method) {
        if (receiver instanceof JsString string) {
            return string;
        }
        if (unwrap(receiver) instanceof JsString wrapped) {
            return wrapped;
        }
        if (receiver instanceof JsNull || receiver instanceof JsUndefined) {
            throw IntrinsicsBrands.incompatible("String.prototype." + method, receiver);
        }
        return new JsString(JsCoercion.toStr(receiver, ops));
    }

    public JsValue toObject(JsValue value) {
        return coercion.toObject(value);
    }

    public JsObject wrapPrimitive(JsValue primitive, JsValue proto) {
        return coercion.wrapPrimitive(primitive, proto);
    }

    public void poison(JsValue target, String key) {
        prototypes.poison(target, key);
    }

    public void linkFunctionKindConstructors(JsValue functionConstructor) {
        prototypes.linkFunctionKindConstructors(functionConstructor);
    }

    public void linkIteratorPrototypes(JsValue iteratorPrototype, JsValue asyncIteratorPrototype) {
        prototypes.linkIteratorPrototypes(iteratorPrototype, asyncIteratorPrototype);
    }

    public JsValue callArrayMethod(JsValue thisArg, String name, List<JsValue> args) {
        return coercion.callArrayMethod(thisArg, name, args);
    }

    public JsNativeFunction wrapper(String name, String label, MethodResolver resolver) {
        return coercion.wrapper(name, label, resolver);
    }

}
