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
        ErrorBuiltins.install(global, intrinsics, ops, iterableToList);
        constructor(global, "Object", ObjectBuiltins.create(iterableToList, ops, invoker, intrinsics),
                intrinsics.objectProto());
        final var functionCtor = functionConstructor();
        constructor(global, "Function", functionCtor, intrinsics.functionProto());
        // %GeneratorFunction%/%AsyncGeneratorFunction%/%AsyncFunction% are subclasses of %Function%
        // per spec ([[Prototype]] = Function itself), but Function doesn't exist yet when
        // functionKindPrototype builds them - stitch the link now that it does.
        intrinsics.linkFunctionKindConstructors(functionCtor);
        constructor(global, "Array", ArrayBuiltins.create(invoker, eventLoop, ops, intrinsics),
                intrinsics.arrayProto());
        constructor(global, "String", StringBuiltins.create(ops), intrinsics.stringProto());
        constructor(global, "Number", NumberBuiltins.create(ops), intrinsics.numberProto());
        constructor(global, "Boolean", booleanFunction(), intrinsics.booleanProto());
        namespace(global, "Math", MathBuiltins.create(ops), intrinsics);
        namespace(global, "JSON", JsonBuiltins.create(ops, invoker, intrinsics.objectProto()), intrinsics);
        namespace(global, "console",
                consoleSink == null ? ConsoleBuiltins.create() : ConsoleBuiltins.create(consoleSink), intrinsics);
        constructor(global, "Promise", PromiseBuiltins.create(eventLoop, invoker, intrinsics),
                intrinsics.promiseProto());
        TimerBuiltins.install(global, eventLoop, invoker);
        constructor(global, "RegExp", RegexBuiltins.create(ops), intrinsics.regexpProto());
        // Iterator/AsyncIterator wire their own dedicated [[Prototype]] internally (it's their own
        // map/filter/... helper surface, not the unrelated Generator/AsyncGenerator.prototype
        // intrinsic `constructor(...)` below would otherwise overwrite it with).
        final var iteratorCtor = IteratorBuiltins.create(ops, intrinsics.objectProto());
        final var asyncIteratorCtor = AsyncIteratorBuiltins.create(ops, eventLoop);
        global.declareBuiltin("Iterator", iteratorCtor);
        global.declareBuiltin("AsyncIterator", asyncIteratorCtor);
        // The spec chain is generator -> %GeneratorPrototype% -> %IteratorPrototype% -> Object.prototype
        // (and the async mirror); Iterator/AsyncIterator own the middle link, so it can only be
        // stitched once both they and the intrinsics exist.
        intrinsics.linkIteratorPrototypes(iteratorCtor.getPrototype(), asyncIteratorCtor.getPrototype());
        constructor(global, "Symbol", SymbolBuiltins.create(ops), intrinsics.symbolProto());
        constructor(global, "Map", MapBuiltins.create(iterableToList, invoker, ops, false), intrinsics.mapProto());
        constructor(global, "WeakMap", MapBuiltins.create(iterableToList, invoker, ops, true),
                intrinsics.weakMapProto());
        constructor(global, "Set", SetBuiltins.create(ops, false), intrinsics.setProto());
        constructor(global, "WeakSet", SetBuiltins.create(ops, true), intrinsics.weakSetProto());
        constructor(global, "Date", DateBuiltins.create(ops), intrinsics.dateProto());
        constructor(global, "DisposableStack",
                DisposableStackBuiltins.create(intrinsics.disposableStackProto(), false, ops),
                intrinsics.disposableStackProto());
        constructor(global, "AsyncDisposableStack",
                DisposableStackBuiltins.create(intrinsics.asyncDisposableStackProto(), true, ops),
                intrinsics.asyncDisposableStackProto());
        namespace(global, "Reflect", ReflectBuiltins.create(ops), intrinsics);
        final var proxyCtor = ProxyBuiltins.create();
        proxyCtor.markConstructor();
        define(global, "Proxy", proxyCtor);
        constructor(global, "ArrayBuffer", TypedArrayBuiltins.arrayBuffer(ops), intrinsics.arrayBufferProto());
        constructor(global, "DataView", TypedArrayBuiltins.dataView(ops), intrinsics.dataViewProto());
        // %TypedArray% is a real spec intrinsic but - unlike Iterator - is never itself exposed as
        // a named global by a conforming host; it's only reachable via Object.getPrototypeOf(Int8Array)
        // (which testTypedArray.js's own `var TypedArray = ...` line relies on), so this wires the
        // constructor-level [[Prototype]] chain without declaring a "TypedArray" global binding.
        final var typedArrayCtor = TypedArrayBuiltins.abstractTypedArray(invoker, iterableToList, ops);
        typedArrayCtor.setPrototype(intrinsics.typedArrayProto());
        typedArrayCtor.markConstructor();
        intrinsics.typedArrayProto().defineValue("constructor", typedArrayCtor);
        intrinsics.typedArrayProto().setFlags("constructor", new JsObject.PropertyFlags(true, false, true));
        for (final var kind : JsTypedArray.Kind.values()) {
            final var ctor = TypedArrayBuiltins.create(kind, iterableToList, ops);
            ctor.setOwnProto(typedArrayCtor);
            constructor(global, kind.ctorName(), ctor, intrinsics.typedArrayProto(kind));
        }
        final var bigInt = NumberBuiltins.bigIntFunction(ops);
        BigIntBuiltins.installStatics(bigInt, ops);
        constructor(global, "BigInt", bigInt, intrinsics.bigintProto());
        define(global, "parseInt", NumberBuiltins.parseIntFunction(ops));
        define(global, "parseFloat", NumberBuiltins.parseFloatFunction(ops));
        define(global, "isNaN", NumberBuiltins.isNaNFunction(ops));
        define(global, "isFinite", NumberBuiltins.isFiniteFunction(ops));
        GlobalFunctionsBuiltins.install(global, eventLoop, invoker, ops, intrinsics);
        FetchBuiltins.install(global, eventLoop, network, limits, intrinsics);
        define(global, "globalThis", globalThis);
        applyStaticLengths(global);
        return globalThis;
    }

    // Static builtins are installed one-by-one across a dozen files with no shared choke point, so
    // their spec `length` is stamped on in a single pass here rather than at each construction site.
    private static void applyStaticLengths(Environment global) {
        for (final var owner : BuiltinLengths.staticOwners()) {
            final var value = global.tryGet(owner);
            if (value instanceof JsNativeFunction ownerFunction) {
                setLength(ownerFunction, BuiltinLengths.globalLength(owner, ownerFunction.getExplicitLength()));
                for (final var key : ownerFunction.propertyKeys()) {
                    if (ownerFunction.getProperty(key) instanceof JsNativeFunction member) {
                        setLength(member, BuiltinLengths.lengthOf(owner, key));
                    }
                }
            } else if (value instanceof JsObject namespace) {
                for (final var key : namespace.keys()) {
                    if (namespace.get(key) instanceof JsNativeFunction member) {
                        setLength(member, BuiltinLengths.lengthOf(owner, key));
                    }
                }
            }
        }
        for (final var name : BuiltinLengths.plainGlobals()) {
            if (global.tryGet(name) instanceof JsNativeFunction function) {
                setLength(function, BuiltinLengths.globalLength(name, 1));
            }
        }
    }

    private static void setLength(JsNativeFunction function, int length) {
        if (!function.hasExplicitLength()) {
            function.setLength(length);
        }
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
        value.markConstructor();
        proto.defineValue("constructor", value);
        proto.setFlags("constructor", new JsObject.PropertyFlags(true, false, true));
        global.declareBuiltin(name, value);
    }

    private static void define(Environment global, String name, JsValue value) {
        global.declareBuiltin(name, value);
    }

    // A namespace object (Math, JSON, Reflect, console) is an ordinary object, so its [[Prototype]]
    // is Object.prototype - without the link Object.getPrototypeOf(Math) answers null.
    private static void namespace(Environment global, String name, JsObject value, Intrinsics intrinsics) {
        value.setProto(intrinsics.objectProto());
        global.declareBuiltin(name, value);
    }
}
