package org.techhouse.simplejs.builtins;

import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.techhouse.simplejs.builtins.typedarray.ArrayBufferBuiltins;
import org.techhouse.simplejs.builtins.typedarray.DataViewBuiltins;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.host.NetworkAccess;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.GlobalSink;
import org.techhouse.simplejs.internal.LazyBuiltin;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class GlobalScope {
    private GlobalScope() {
    }

    public static JsGlobalObject install(Environment global, EventLoop eventLoop, Invoker invoker,
            IterableToList iterableToList, Consumer<String> consoleSink, InterpreterOps ops, NetworkAccess network,
            ResourceLimits limits, Intrinsics intrinsics) {
        final var globalThis = new JsGlobalObject(global);
        global.declareNonWritableBuiltin("NaN", new JsNumber(Double.NaN));
        global.declareNonWritableBuiltin("Infinity", new JsNumber(Double.POSITIVE_INFINITY));
        global.declareNonWritableBuiltin("undefined", JsUndefined.getInstance());
        ErrorBuiltins.install(global, intrinsics, ops, iterableToList);
        constructor(global, "Object", () -> ObjectBuiltins.create(iterableToList, ops, invoker, intrinsics),
                intrinsics.objectProto);
        final var functionCtor = functionConstructor();
        eagerFunctionConstructor(global, functionCtor, intrinsics.functionProto);
        intrinsics.linkFunctionKindConstructors(functionCtor);
        constructor(global, "Array", () -> ArrayBuiltins.create(invoker, eventLoop, ops, intrinsics),
                intrinsics.arrayProto);
        constructor(global, "String", () -> StringBuiltins.create(ops), intrinsics.stringProto);
        final var parsers = new HashMap<String, JsValue>();
        final var number = constructor(global, "Number", () -> {
            final var ctor = NumberBuiltins.create(ops);
            parsers.put("parseInt", ctor.getProperty("parseInt"));
            parsers.put("parseFloat", ctor.getProperty("parseFloat"));
            return ctor;
        }, intrinsics.numberProto);
        constructor(global, "Boolean", GlobalScope::booleanFunction, intrinsics.booleanProto);
        namespace(global, "Math", () -> MathBuiltins.create(ops), intrinsics);
        namespace(global, "JSON", () -> JsonBuiltins.create(ops, invoker, intrinsics.objectProto), intrinsics);
        namespace(global, "crypto", () -> CryptoBuiltins.create(ops), intrinsics);
        namespace(global, "console",
                () -> consoleSink == null ? ConsoleBuiltins.create() : ConsoleBuiltins.create(consoleSink), intrinsics);
        constructor(global, "Promise", () -> PromiseBuiltins.create(eventLoop, invoker, intrinsics),
                intrinsics.promiseProto);
        group(global, TimerBuiltins.NAMES, sink -> TimerBuiltins.install(sink, eventLoop, invoker));
        constructor(global, "RegExp", () -> RegexBuiltins.create(ops), intrinsics.regexpProto);
        final var iteratorCtor = IteratorBuiltins.create(ops, intrinsics.objectProto);
        final var asyncIteratorCtor = AsyncIteratorBuiltins.create(ops, eventLoop);
        global.declareBuiltin("Iterator", iteratorCtor);
        global.declareBuiltin("AsyncIterator", asyncIteratorCtor);
        intrinsics.linkIteratorPrototypes(iteratorCtor.getPrototype(), asyncIteratorCtor.getPrototype());
        constructor(global, "Symbol", () -> SymbolBuiltins.create(ops), intrinsics.symbolProto);
        constructor(global, "Map", () -> MapBuiltins.create(iterableToList, invoker, ops, false), intrinsics.mapProto);
        constructor(global, "WeakMap", () -> MapBuiltins.create(iterableToList, invoker, ops, true),
                intrinsics.weakMapProto);
        constructor(global, "Set", () -> SetBuiltins.create(ops, false), intrinsics.setProto);
        constructor(global, "WeakSet", () -> SetBuiltins.create(ops, true), intrinsics.weakSetProto);
        constructor(global, "Date", () -> DateBuiltins.create(ops), intrinsics.dateProto);
        constructor(global, "Geo", () -> GeoBuiltins.create(ops), intrinsics.geoProto);
        constructor(global, "Vector", () -> VectorBuiltins.create(ops), intrinsics.vectorProto);
        constructor(global, "DbDateTime", () -> DbDateTimeBuiltins.create(ops), intrinsics.dbDateTimeProto);
        constructor(global, "DbTime", () -> DbTimeBuiltins.create(ops), intrinsics.dbTimeProto);
        final var temporal = group(global, TemporalBuiltins.NAMES,
                sink -> TemporalBuiltins.install(sink, intrinsics, ops));
        for (final var proto : List.of(intrinsics.temporalDurationProto, intrinsics.temporalPlainTimeProto,
                intrinsics.temporalPlainDateProto, intrinsics.temporalInstantProto,
                intrinsics.temporalPlainYearMonthProto, intrinsics.temporalPlainMonthDayProto,
                intrinsics.temporalPlainDateTimeProto, intrinsics.temporalZonedDateTimeProto)) {
            proto.deferSetup(_ -> temporal.get());
        }
        constructor(global, "DisposableStack",
                () -> DisposableStackBuiltins.create(intrinsics.disposableStackProto, false, ops),
                intrinsics.disposableStackProto);
        constructor(global, "AsyncDisposableStack",
                () -> DisposableStackBuiltins.create(intrinsics.asyncDisposableStackProto, true, ops),
                intrinsics.asyncDisposableStackProto);
        namespace(global, "Reflect", () -> ReflectBuiltins.create(ops), intrinsics);
        define(global, "Proxy", () -> {
            final var proxyCtor = ProxyBuiltins.create();
            proxyCtor.markConstructor();
            return proxyCtor;
        });
        constructor(global, "ArrayBuffer", () -> ArrayBufferBuiltins.arrayBuffer(ops), intrinsics.arrayBufferProto);
        constructor(global, "DataView", () -> DataViewBuiltins.dataView(ops), intrinsics.dataViewProto);
        installTypedArrays(global, invoker, iterableToList, ops, intrinsics);
        constructor(global, "BigInt", () -> {
            final var bigInt = NumberBuiltins.bigIntFunction(ops);
            BigIntBuiltins.installStatics(bigInt, ops);
            return bigInt;
        }, intrinsics.bigintProto);
        for (final var parser : List.of("parseInt", "parseFloat")) {
            define(global, parser, () -> {
                number.get();
                return parsers.get(parser);
            });
        }
        define(global, "isNaN", () -> NumberBuiltins.isNaNFunction(ops));
        define(global, "isFinite", () -> NumberBuiltins.isFiniteFunction(ops));
        define(global, "eval", GlobalScope::evalStub);
        group(global, GlobalFunctionsBuiltins.NAMES,
                sink -> GlobalFunctionsBuiltins.install(sink, eventLoop, invoker, ops, intrinsics));
        group(global, FetchBuiltins.NAMES,
                sink -> FetchBuiltins.install(sink, eventLoop, network, limits, intrinsics, ops));
        global.declareBuiltin("globalThis", globalThis);
        staticLengths("Iterator", iteratorCtor);
        staticLengths("AsyncIterator", asyncIteratorCtor);
        staticLengths("Function", functionCtor);
        return globalThis;
    }

    private static void installTypedArrays(Environment global, Invoker invoker, IterableToList iterableToList,
            InterpreterOps ops, Intrinsics intrinsics) {
        final var typedArrayCtor = new LazyBuiltin(() -> {
            final var ctor = TypedArrayBuiltins.abstractTypedArray(invoker, iterableToList, ops);
            ctor.setPrototype(intrinsics.typedArrayProto);
            ctor.markConstructor();
            return ctor;
        });
        intrinsics.typedArrayProto.deferSetup(proto -> {
            proto.defineValue("constructor", typedArrayCtor.get());
            proto.setFlags("constructor", JsObject.PropertyFlags.HIDDEN);
        });
        for (final var kind : JsTypedArray.Kind.values()) {
            constructor(global, kind.ctorName(), () -> {
                final var ctor = TypedArrayBuiltins.create(kind, iterableToList, ops);
                ctor.setOwnProto(typedArrayCtor.get());
                return ctor;
            }, intrinsics.typedArrayProto(kind));
        }
    }

    private static void staticLengths(String name, JsValue value) {
        if (BuiltinLengths.staticOwners().contains(name)) {
            final var lengths = BuiltinLengths.lengthsFor(name);
            if (value instanceof JsNativeFunction ownerFunction) {
                setLength(ownerFunction, BuiltinLengths.globalLength(name, ownerFunction.getExplicitLength()));
                for (final var key : ownerFunction.propertyKeys()) {
                    if (ownerFunction.getProperty(key) instanceof JsNativeFunction member) {
                        setLength(member, BuiltinLengths.lengthIn(lengths, key));
                    }
                }
            } else if (value instanceof JsObject namespace) {
                for (final var key : namespace.keys()) {
                    if (namespace.get(key) instanceof JsNativeFunction member) {
                        setLength(member, BuiltinLengths.lengthIn(lengths, key));
                    }
                }
            }
        }
        if (BuiltinLengths.plainGlobals().contains(name) && value instanceof JsNativeFunction function) {
            setLength(function, BuiltinLengths.globalLength(name, 1));
        }
    }

    private static void setLength(JsNativeFunction function, int length) {
        if (!function.hasExplicitLength()) {
            function.setLength(length);
        }
    }

    private static JsNativeFunction functionConstructor() {
        return new JsNativeFunction("Function", (_, _) -> {
            throw new TypeErrorException("Function constructor is disabled");
        });
    }

    private static JsNativeFunction booleanFunction() {
        return new JsNativeFunction("Boolean", (_, args) -> JsBoolean
                .of(!args.isEmpty() && org.techhouse.simplejs.internal.JsCoercion.toBoolean(args.getFirst())));
    }

    private static JsNativeFunction evalStub() {
        final var fn = new JsNativeFunction("eval", (_, _) -> {
            throw new TypeErrorException("eval is not supported: SimpleJS has no runtime code generation");
        });
        fn.setLength(1);
        return fn;
    }

    private static void eagerFunctionConstructor(Environment global, JsNativeFunction value, JsObject proto) {
        value.setPrototype(proto);
        value.markConstructor();
        linkConstructor(proto, () -> value);
        global.declareBuiltin("Function", value);
    }

    private static LazyBuiltin constructor(Environment global, String name, Supplier<JsNativeFunction> factory,
            JsObject proto) {
        final var holder = new LazyBuiltin(() -> {
            final var value = factory.get();
            value.setPrototype(proto);
            value.markConstructor();
            staticLengths(name, value);
            return value;
        });
        linkConstructor(proto, holder::get);
        global.declareLazyBuiltin(name, holder);
        return holder;
    }

    private static void linkConstructor(JsObject proto, Supplier<JsValue> constructor) {
        proto.deferSetup(target -> {
            target.defineValue("constructor", constructor.get());
            target.setFlags("constructor", JsObject.PropertyFlags.HIDDEN);
        });
    }

    private static void define(Environment global, String name, Supplier<JsValue> factory) {
        global.declareLazyBuiltin(name, new LazyBuiltin(() -> {
            final var value = factory.get();
            staticLengths(name, value);
            return value;
        }));
    }

    private static void namespace(Environment global, String name, Supplier<JsObject> factory, Intrinsics intrinsics) {
        define(global, name, () -> {
            final var value = factory.get();
            value.setProto(intrinsics.objectProto);
            return value;
        });
    }

    static LazyBuiltin group(Environment global, List<String> names, Consumer<GlobalSink> installer) {
        final var members = new HashMap<String, JsValue>();
        final var installed = new LazyBuiltin(() -> {
            installer.accept(members::put);
            return JsUndefined.getInstance();
        });
        for (final var name : names) {
            define(global, name, () -> {
                installed.get();
                return members.get(name);
            });
        }
        return installed;
    }
}
