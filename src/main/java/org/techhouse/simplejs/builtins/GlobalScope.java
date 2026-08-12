package org.techhouse.simplejs.builtins;

import java.util.function.Consumer;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.host.NetworkAccess;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.EventLoop;
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
        ErrorBuiltins.install(global, intrinsics);
        constructor(global, "Object", ObjectBuiltins.create(iterableToList, ops, invoker), intrinsics.objectProto());
        constructor(global, "Function", functionConstructor(), intrinsics.functionProto());
        constructor(global, "Array", ArrayBuiltins.create(invoker, iterableToList, eventLoop, ops),
                intrinsics.arrayProto());
        constructor(global, "String", StringBuiltins.create(ops), intrinsics.stringProto());
        constructor(global, "Number", NumberBuiltins.create(), intrinsics.numberProto());
        constructor(global, "Boolean", booleanFunction(), intrinsics.booleanProto());
        define(global, "Math", MathBuiltins.create(iterableToList));
        define(global, "JSON", JsonBuiltins.create(ops, invoker));
        define(global, "console", consoleSink == null ? ConsoleBuiltins.create() : ConsoleBuiltins.create(consoleSink));
        constructor(global, "Promise", PromiseBuiltins.create(eventLoop, invoker, iterableToList),
                intrinsics.promiseProto());
        TimerBuiltins.install(global, eventLoop, invoker);
        constructor(global, "RegExp", RegexBuiltins.create(), intrinsics.regexpProto());
        constructor(global, "Iterator", IteratorBuiltins.create(ops), intrinsics.iteratorProto());
        constructor(global, "AsyncIterator", AsyncIteratorBuiltins.create(ops, eventLoop),
                intrinsics.asyncIteratorProto());
        constructor(global, "Symbol", SymbolBuiltins.create(), intrinsics.symbolProto());
        constructor(global, "Map", MapBuiltins.create(iterableToList, invoker, false), intrinsics.mapProto());
        constructor(global, "WeakMap", MapBuiltins.create(iterableToList, invoker, true), intrinsics.mapProto());
        constructor(global, "Set", SetBuiltins.create(iterableToList, false), intrinsics.setProto());
        constructor(global, "WeakSet", SetBuiltins.create(iterableToList, true), intrinsics.setProto());
        constructor(global, "Date", DateBuiltins.create(), intrinsics.dateProto());
        constructor(global, "DisposableStack", DisposableStackBuiltins.create(intrinsics.disposableStackProto(), false),
                intrinsics.disposableStackProto());
        constructor(global, "AsyncDisposableStack",
                DisposableStackBuiltins.create(intrinsics.asyncDisposableStackProto(), true),
                intrinsics.asyncDisposableStackProto());
        define(global, "Reflect", ReflectBuiltins.create(ops));
        define(global, "Proxy", ProxyBuiltins.create());
        constructor(global, "ArrayBuffer", TypedArrayBuiltins.arrayBuffer(), intrinsics.arrayBufferProto());
        constructor(global, "DataView", TypedArrayBuiltins.dataView(), intrinsics.dataViewProto());
        for (final var kind : JsTypedArray.Kind.values()) {
            constructor(global, kind.ctorName(), TypedArrayBuiltins.create(kind, invoker, iterableToList, ops),
                    intrinsics.typedArrayProto(kind));
        }
        final var bigInt = NumberBuiltins.bigIntFunction();
        BigIntBuiltins.installStatics(bigInt);
        constructor(global, "BigInt", bigInt, intrinsics.bigintProto());
        define(global, "parseInt", NumberBuiltins.parseIntFunction());
        define(global, "parseFloat", NumberBuiltins.parseFloatFunction());
        define(global, "isNaN", NumberBuiltins.isNaNFunction());
        define(global, "isFinite", NumberBuiltins.isFiniteFunction());
        GlobalFunctionsBuiltins.install(global, eventLoop, invoker, ops);
        FetchBuiltins.install(global, eventLoop, network, limits);
        define(global, "globalThis", globalThis);
        return globalThis;
    }

    // Installed only so `Function.prototype` resolves and `f instanceof Function` works: runtime code
    // generation from strings stays outside the sandbox (see the deliberate-omissions list).
    private static JsNativeFunction functionConstructor() {
        return new JsNativeFunction("Function", (_, _) -> {
            throw new TypeErrorException("Function constructor is disabled");
        });
    }

    private static JsNativeFunction booleanFunction() {
        return new JsNativeFunction("Boolean", (_, args) -> JsBoolean
                .of(!args.isEmpty() && org.techhouse.simplejs.internal.JsCoercion.toBoolean(args.getFirst())));
    }

    private static void constructor(Environment global, String name, JsNativeFunction value, JsObject proto) {
        value.setPrototype(proto);
        proto.defineValue("constructor", value);
        proto.setFlags("constructor", new JsObject.PropertyFlags(true, false, true));
        global.declareBuiltin(name, value);
    }

    private static void define(Environment global, String name, JsValue value) {
        global.declareBuiltin(name, value);
    }
}
