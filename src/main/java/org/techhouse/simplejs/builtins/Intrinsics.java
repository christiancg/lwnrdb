package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireBigInt;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireBuffer;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireCallable;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireDate;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireDbDateTime;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireDbTime;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireGenerator;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireGeo;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireMap;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireNumber;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireRegExp;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireSet;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireStack;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireSymbol;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalDuration;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalInstant;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalPlainDate;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalPlainDateTime;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalPlainMonthDay;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalPlainTime;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalPlainYearMonth;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalZonedDateTime;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTypedArray;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireUint8;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireVector;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireView;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.unwrap;
import static org.techhouse.simplejs.values.JsObject.PropertyFlags.HIDDEN;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands;
import org.techhouse.simplejs.builtins.intrinsics.IntrinsicsCoercion;
import org.techhouse.simplejs.builtins.intrinsics.IntrinsicsInstallers;
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
    public JsValue defaultHasInstance;
    public JsValue defaultArrayIterator;
    public JsValue defaultStringIterator;
    public JsValue defaultTypedArrayIterator;
    public final Map<String, JsObject> errorProtos = new LinkedHashMap<>();

    public final IntrinsicsInstallers installers = new IntrinsicsInstallers(this);

    public boolean isDefaultHasInstance(JsValue candidate) {
        return installers.isDefaultHasInstance(candidate);
    }

    public boolean isDefaultIterator(JsValue target, JsValue candidate) {
        return installers.isDefaultIterator(target, candidate);
    }

    public final IntrinsicsPrototypes prototypes = new IntrinsicsPrototypes(this);
    public final IntrinsicsCoercion coercion = new IntrinsicsCoercion(this);

    public Intrinsics(Invoker invoker, InterpreterOps ops, EventLoop eventLoop, GeneratorBuiltins.AsyncDriver driver) {
        this.invoker = invoker;
        this.ops = ops;
        promiseProto = coercion.prototypeOf(PromiseBuiltins.PROTO_NAMES, "Promise.prototype",
                (receiver, name) -> PromiseBuiltins.getMethod(receiver, name, eventLoop, invoker, this));
        iteratorProto = coercion.prototypeOf(GeneratorBuiltins.PROTO_NAMES, "Generator.prototype",
                (receiver, name) -> GeneratorBuiltins.getMethod(requireGenerator(receiver, name), name, objectProto));
        asyncIteratorProto = coercion.prototypeOf(GeneratorBuiltins.PROTO_NAMES, "AsyncGenerator.prototype",
                (receiver, name) -> installers.asyncGeneratorMethod(receiver, name, driver, eventLoop));
        functionProto = coercion.prototypeOf(FunctionProtoBuiltins.NAMES, "Function.prototype", (receiver,
                name) -> FunctionProtoBuiltins.getMethod(requireCallable(receiver, name), name, invoker, ops));
        installers.installFunctionHasInstance(functionProto);
        functionProto.defineValue("length", new JsNumber(0));
        functionProto.setFlags("length", PropertyFlags.TAG);
        functionProto.defineValue("name", new JsString(""));
        functionProto.setFlags("name", PropertyFlags.TAG);
        IntrinsicsInstallers.defineToStringTag(functionProto, "Function");
        arrayProto = prototypes.arrayPrototype();
        stringProto = coercion.prototypeOf(StringBuiltins.NAMES, "String.prototype",
                (receiver, name) -> StringRegExpDelegation.isGeneric(name)
                        ? StringRegExpDelegation.genericMethod(name, ops, invoker)
                        : StringBuiltins.getMethod(requireStringValue(receiver, name), name, invoker, ops));
        prototypes.installStringPrimitiveMethods(stringProto);
        numberProto = coercion.prototypeOf(NumberBuiltins.NAMES, "Number.prototype",
                (receiver, name) -> NumberBuiltins.getMethod(requireNumber(receiver, name), name, ops));
        booleanProto = prototypes.booleanPrototype();
        stringProto.setPrimitive(new JsString(""));
        numberProto.setPrimitive(new JsNumber(0));
        booleanProto.setPrimitive(JsBoolean.FALSE);
        bigintProto = coercion.prototypeOf(BigIntBuiltins.NAMES, "BigInt.prototype",
                (receiver, name) -> BigIntBuiltins.getMethod(requireBigInt(receiver, name), name, ops));
        symbolProto = coercion.prototypeOf(SymbolBuiltins.NAMES, "Symbol.prototype",
                (receiver, name) -> SymbolBuiltins.getMethod(requireSymbol(receiver, name), name));
        installers.installSymbolAccessors(symbolProto);
        regexpProto = coercion.prototypeOf(RegexBuiltins.NAMES, "RegExp.prototype",
                (receiver, name) -> RegexBuiltins.getMethod(requireRegExp(receiver, name), name, ops));
        installers.installRegExpSymbolMethods(regexpProto);
        installers.installRegExpAccessors(regexpProto);
        mapProto = coercion.prototypeOf(MapBuiltins.NAMES, "Map.prototype",
                (receiver, name) -> MapBuiltins.getMethod(requireMap(receiver, name, false), name, invoker));
        installers.installMapSizeAccessor(mapProto);
        weakMapProto = coercion.prototypeOf(MapBuiltins.WEAK_NAMES, "WeakMap.prototype",
                (receiver, name) -> MapBuiltins.getMethod(requireMap(receiver, name, true), name, invoker));
        setProto = coercion.prototypeOf(SetBuiltins.NAMES, "Set.prototype",
                (receiver, name) -> SetBuiltins.getMethod(requireSet(receiver, name, false), name, invoker, ops));
        installers.installSetSizeAccessor(setProto);
        weakSetProto = coercion.prototypeOf(SetBuiltins.WEAK_NAMES, "WeakSet.prototype",
                (receiver, name) -> SetBuiltins.getMethod(requireSet(receiver, name, true), name, invoker, ops));
        dateProto = coercion.prototypeOf(DateBuiltins.NAMES, "Date.prototype",
                (receiver, name) -> DateBuiltins.isGeneric(name)
                        ? DateBuiltins.genericMethod(name, ops)
                        : DateBuiltins.getMethod(requireDate(receiver, name), name, ops));
        installToPrimitive(dateProto, DateBuiltins.symbolToPrimitive(ops));
        temporalDurationProto = temporalDurationPrototype();
        temporalPlainTimeProto = coercion.prototypeOf(TemporalPlainTimeBuiltins.NAMES, "Temporal.PlainTime.prototype",
                (receiver, name) -> TemporalPlainTimeBuiltins.getMethod(requireTemporalPlainTime(receiver, name), name,
                        ops));
        installers.installTemporalPlainTimeAccessors(temporalPlainTimeProto);
        IntrinsicsInstallers.defineToStringTag(temporalPlainTimeProto, "Temporal.PlainTime");
        temporalPlainDateProto = coercion.prototypeOf(TemporalPlainDateBuiltins.NAMES, "Temporal.PlainDate.prototype",
                (receiver, name) -> TemporalPlainDateBuiltins.getMethod(requireTemporalPlainDate(receiver, name), name,
                        ops));
        TemporalPlainDateBuiltins.installAccessors(temporalPlainDateProto);
        temporalInstantProto = coercion.prototypeOf(TemporalInstantBuiltins.NAMES, "Temporal.Instant.prototype",
                (receiver, name) -> TemporalInstantBuiltins.getMethod(requireTemporalInstant(receiver, name), name,
                        ops));
        TemporalInstantBuiltins.installAccessors(temporalInstantProto);
        temporalPlainYearMonthProto = coercion.prototypeOf(TemporalPlainYearMonthBuiltins.NAMES,
                "Temporal.PlainYearMonth.prototype", (receiver, name) -> TemporalPlainYearMonthBuiltins
                        .getMethod(requireTemporalPlainYearMonth(receiver, name), name, ops));
        TemporalPlainYearMonthBuiltins.installAccessors(temporalPlainYearMonthProto);
        IntrinsicsInstallers.defineToStringTag(temporalPlainYearMonthProto, "Temporal.PlainYearMonth");
        temporalPlainMonthDayProto = coercion.prototypeOf(TemporalPlainMonthDayBuiltins.NAMES,
                "Temporal.PlainMonthDay.prototype", (receiver, name) -> TemporalPlainMonthDayBuiltins
                        .getMethod(requireTemporalPlainMonthDay(receiver, name), name, ops));
        TemporalPlainMonthDayBuiltins.installAccessors(temporalPlainMonthDayProto);
        IntrinsicsInstallers.defineToStringTag(temporalPlainMonthDayProto, "Temporal.PlainMonthDay");
        temporalPlainDateTimeProto = coercion.prototypeOf(TemporalPlainDateTimeBuiltins.NAMES,
                "Temporal.PlainDateTime.prototype", (receiver, name) -> TemporalPlainDateTimeBuiltins
                        .getMethod(requireTemporalPlainDateTime(receiver, name), name, ops));
        TemporalPlainDateTimeBuiltins.installAccessors(temporalPlainDateTimeProto);
        temporalZonedDateTimeProto = coercion.prototypeOf(TemporalZonedDateTimeBuiltins.NAMES,
                "Temporal.ZonedDateTime.prototype", (receiver, name) -> TemporalZonedDateTimeBuiltins
                        .getMethod(requireTemporalZonedDateTime(receiver, name), name, ops));
        TemporalZonedDateTimeBuiltins.installAccessors(temporalZonedDateTimeProto);
        geoProto = coercion.prototypeOf(GeoBuiltins.NAMES, "Geo.prototype",
                (receiver, name) -> GeoBuiltins.getMethod(requireGeo(receiver, name), name));
        GeoBuiltins.installAccessors(geoProto);
        IntrinsicsInstallers.defineToStringTag(geoProto, "Geo");
        vectorProto = coercion.prototypeOf(VectorBuiltins.NAMES, "Vector.prototype",
                (receiver, name) -> VectorBuiltins.getMethod(requireVector(receiver, name), name, ops));
        VectorBuiltins.installAccessors(vectorProto);
        IntrinsicsInstallers.defineToStringTag(vectorProto, "Vector");
        dbDateTimeProto = coercion.prototypeOf(DbDateTimeBuiltins.NAMES, "DbDateTime.prototype",
                (receiver, name) -> DbDateTimeBuiltins.getMethod(requireDbDateTime(receiver, name), name));
        DbDateTimeBuiltins.installAccessors(dbDateTimeProto);
        IntrinsicsInstallers.defineToStringTag(dbDateTimeProto, "DbDateTime");
        dbTimeProto = coercion.prototypeOf(DbTimeBuiltins.NAMES, "DbTime.prototype",
                (receiver, name) -> DbTimeBuiltins.getMethod(requireDbTime(receiver, name), name));
        DbTimeBuiltins.installAccessors(dbTimeProto);
        IntrinsicsInstallers.defineToStringTag(dbTimeProto, "DbTime");
        arrayBufferProto = coercion.prototypeOf(TypedArrayBuiltins.BUFFER_NAMES, "ArrayBuffer.prototype",
                (receiver, name) -> ArrayBufferBuiltins.bufferMethod(requireBuffer(receiver, name), name, ops));
        dataViewProto = coercion.prototypeOf(TypedArrayBuiltins.VIEW_NAMES, "DataView.prototype",
                (receiver, name) -> DataViewBuiltins.dataViewMethod(requireView(receiver, name), name, ops));
        typedArrayProto = coercion.prototypeOf(TypedArrayBuiltins.NAMES, "TypedArray.prototype", (receiver,
                name) -> TypedArrayIteration.getMethod(requireTypedArray(receiver, name), name, invoker, ops));
        define(typedArrayProto, "toString", arrayProto.get("toString"));
        installers.defineSymbol(typedArrayProto, JsSymbol.ITERATOR, typedArrayProto.get("values"));
        installers.installTypedArrayGeometryAccessors(typedArrayProto);
        installers.installTypedArrayToStringTag(typedArrayProto);
        installers.installGeometryAccessors(arrayBufferProto, ArrayBufferBuiltins.bufferAccessorNames(),
                (thisArg, name) -> ArrayBufferBuiltins.bufferMethod(requireBuffer(thisArg, name), name));
        installers.installGeometryAccessors(dataViewProto, DataViewBuiltins.viewAccessorNames(),
                (thisArg, name) -> DataViewBuiltins.dataViewMethod(requireView(thisArg, name), name));
        for (final var kind : JsTypedArray.Kind.values()) {
            final var proto = new JsObject();
            proto.setProto(typedArrayProto);
            TypedArrayBuiltins.defineBytesPerElement(proto.ownProperties(), kind);
            typedArrayProtos.put(kind, proto);
        }
        for (final var name : TypedArrayBuiltins.UINT8_NAMES) {
            define(typedArrayProtos.get(JsTypedArray.Kind.UINT8), name, coercion.wrapper(name, "Uint8Array.prototype",
                    (receiver, key) -> Uint8Base64.uint8Method(requireUint8(receiver, key), key, ops)));
        }
        disposableStackProto = coercion.prototypeOf(DisposableStackBuiltins.NAMES, "DisposableStack.prototype",
                (receiver, name) -> DisposableStackBuiltins.getMethod(requireStack(receiver, name), name, ops, invoker,
                        eventLoop, false));
        DisposableStackBuiltins.installAccessors(disposableStackProto, false);
        asyncDisposableStackProto = coercion.prototypeOf(DisposableStackBuiltins.ASYNC_NAMES,
                "AsyncDisposableStack.prototype",
                (receiver, name) -> "disposeAsync".equals(name)
                        ? installers.disposeAsyncMethod(receiver, name, eventLoop)
                        : DisposableStackBuiltins.getMethod(requireStack(receiver, name), name, ops, invoker, eventLoop,
                                true));
        DisposableStackBuiltins.installAccessors(asyncDisposableStackProto, true);
        prototypes.installObjectPrototype();
        prototypes.installErrorPrototypes();
        installers.installIteratorSymbols();
        installers.installToStringTags();
        installers.installArrayUnscopables();
        throwTypeError = IntrinsicsPrototypes.makeThrowTypeError();
        prototypes.installPoisonPill(functionProto, "caller");
        prototypes.installPoisonPill(functionProto, "arguments");
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
        return ErrorBuiltins.makeError(name, message, errorProto(name));
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
            define(proto, name, coercion.wrapper(name, "Temporal.Duration.prototype", (receiver,
                    key) -> TemporalDurationBuiltins.getMethod(requireTemporalDuration(receiver, key), key, ops)));
        }
        proto.setProto(objectProto);
        return proto;
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
