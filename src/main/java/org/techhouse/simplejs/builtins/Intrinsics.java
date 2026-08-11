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
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
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
            "URIError", "SuppressedError", "AggregateError");

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
    private final JsObject setProto;
    private final JsObject dateProto;
    private final JsObject promiseProto;
    private final JsObject iteratorProto;
    private final JsObject asyncIteratorProto;
    private final JsObject arrayBufferProto;
    private final JsObject dataViewProto;
    private final Map<JsTypedArray.Kind, JsObject> typedArrayProtos = new EnumMap<>(JsTypedArray.Kind.class);
    private final JsObject disposableStackProto;
    private final JsObject asyncDisposableStackProto;
    private final JsObject errorProto = new JsObject();
    private final Map<String, JsObject> errorProtos = new LinkedHashMap<>();

    public Intrinsics(Invoker invoker, InterpreterOps ops, EventLoop eventLoop, GeneratorBuiltins.AsyncDriver driver) {
        this.invoker = invoker;
        this.ops = ops;
        promiseProto = prototypeOf(PromiseBuiltins.PROTO_NAMES, "Promise.prototype", (receiver, name) -> PromiseBuiltins
                .getMethod(requirePromise(receiver, name), name, eventLoop, invoker, this));
        iteratorProto = prototypeOf(GeneratorBuiltins.PROTO_NAMES, "Generator.prototype",
                (receiver, name) -> GeneratorBuiltins.getMethod(requireGenerator(receiver, name), name));
        asyncIteratorProto = prototypeOf(GeneratorBuiltins.PROTO_NAMES, "AsyncGenerator.prototype", (receiver,
                name) -> GeneratorBuiltins.getAsyncMethod(requireAsyncGenerator(receiver, name), name, driver));
        functionProto = prototypeOf(FunctionProtoBuiltins.NAMES, "Function.prototype",
                (receiver, name) -> FunctionProtoBuiltins.getMethod(requireCallable(receiver, name), name, invoker));
        arrayProto = prototypeOf(ArrayBuiltins.NAMES, "Array.prototype",
                (receiver, name) -> ArrayBuiltins.getMethod(requireArray(receiver, name), name, invoker, ops));
        stringProto = prototypeOf(StringBuiltins.NAMES, "String.prototype",
                (receiver, name) -> StringBuiltins.getMethod(requireString(receiver, name), name, invoker, ops));
        numberProto = prototypeOf(NumberBuiltins.NAMES, "Number.prototype",
                (receiver, name) -> NumberBuiltins.getMethod(requireNumber(receiver, name), name));
        booleanProto = booleanPrototype();
        bigintProto = prototypeOf(BigIntBuiltins.NAMES, "BigInt.prototype",
                (receiver, name) -> BigIntBuiltins.getMethod(requireBigInt(receiver, name), name));
        symbolProto = prototypeOf(SymbolBuiltins.NAMES, "Symbol.prototype",
                (receiver, name) -> SymbolBuiltins.getMethod(requireSymbol(receiver, name), name));
        regexpProto = prototypeOf(RegexBuiltins.NAMES, "RegExp.prototype",
                (receiver, name) -> RegexBuiltins.getMethod(requireRegExp(receiver, name), name));
        mapProto = prototypeOf(MapBuiltins.NAMES, "Map.prototype",
                (receiver, name) -> MapBuiltins.getMethod(requireMap(receiver, name), name, invoker));
        setProto = prototypeOf(SetBuiltins.NAMES, "Set.prototype",
                (receiver, name) -> SetBuiltins.getMethod(requireSet(receiver, name), name, invoker));
        dateProto = prototypeOf(DateBuiltins.NAMES, "Date.prototype",
                (receiver, name) -> DateBuiltins.getMethod(requireDate(receiver, name), name));
        arrayBufferProto = prototypeOf(TypedArrayBuiltins.BUFFER_NAMES, "ArrayBuffer.prototype",
                (receiver, name) -> TypedArrayBuiltins.bufferMethod(requireBuffer(receiver, name), name));
        dataViewProto = prototypeOf(TypedArrayBuiltins.VIEW_NAMES, "DataView.prototype",
                (receiver, name) -> TypedArrayBuiltins.dataViewMethod(requireView(receiver, name), name));
        JsObject typedArrayProto = prototypeOf(TypedArrayBuiltins.NAMES, "TypedArray.prototype",
                (receiver, name) -> TypedArrayBuiltins.getMethod(requireTypedArray(receiver, name), name, invoker));
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
        // Error.prototype is the shared base so `e instanceof Error` holds for every error subtype.
        define(errorProto, "name", new JsString("Error"));
        errorProtos.put("Error", errorProto);
        for (final var name : ERROR_NAMES) {
            if (errorProtos.containsKey(name)) {
                continue;
            }
            final var proto = new JsObject();
            proto.setProto(errorProto);
            define(proto, "name", new JsString(name));
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

    private JsObject prototypeOf(List<String> names, String label, MethodResolver resolver) {
        final var proto = new JsObject();
        for (final var name : names) {
            define(proto, name, wrapper(name, label, resolver));
        }
        proto.setProto(objectProto);
        return proto;
    }

    private JsNativeFunction wrapper(String name, String label, MethodResolver resolver) {
        return new JsNativeFunction(name, (thisArg, args) -> {
            final var method = resolver.resolve(thisArg, name);
            if (method == null) {
                throw incompatible(label + "." + name, thisArg);
            }
            return invoker.call(method, thisArg, args);
        });
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
            case JsMap ignored -> mapProto;
            case JsSet ignored -> setProto;
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

    public JsObject setProto() {
        return setProto;
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

    public JsObject errorProto(String name) {
        return errorProtos.getOrDefault(name, errorProto);
    }

    public JsObject makeError(String name, String message) {
        return ErrorBuiltins.makeError(name, message, errorProto(name));
    }

    // Array.prototype.* accepts any array-like receiver by snapshotting it into a JsArray; a
    // mutating method therefore does not write through to the original (documented limitation).
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
        throw incompatible("Array.prototype." + method, receiver);
    }

    private JsString requireString(JsValue receiver, String method) {
        if (receiver instanceof JsString string) {
            return string;
        }
        if (unwrap(receiver) instanceof JsString wrapped) {
            return wrapped;
        }
        throw incompatible("String.prototype." + method, receiver);
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

    private JsPromise requirePromise(JsValue receiver, String method) {
        if (receiver instanceof JsPromise promise) {
            return promise;
        }
        if (unwrap(receiver) instanceof JsPromise wrapped) {
            return wrapped;
        }
        throw incompatible("Promise.prototype." + method, receiver);
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

    private JsMap requireMap(JsValue receiver, String method) {
        if (receiver instanceof JsMap map) {
            return map;
        }
        if (unwrap(receiver) instanceof JsMap wrapped) {
            return wrapped;
        }
        throw incompatible("Map.prototype." + method, receiver);
    }

    private JsSet requireSet(JsValue receiver, String method) {
        if (receiver instanceof JsSet set) {
            return set;
        }
        if (unwrap(receiver) instanceof JsSet wrapped) {
            return wrapped;
        }
        throw incompatible("Set.prototype." + method, receiver);
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
