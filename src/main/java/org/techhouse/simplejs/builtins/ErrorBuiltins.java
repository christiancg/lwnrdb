package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ErrorBuiltins {
    private static final List<String> NAMES = List.of("Error", "TypeError", "RangeError", "SyntaxError", "URIError",
            "ReferenceError", "EvalError");
    private static final JsObject.PropertyFlags ERROR_PROPERTY = new JsObject.PropertyFlags(true, false, true);

    // makeSuppressedError is called from the using/await-using disposal path (StatementEvaluator,
    // DisposableStackBuiltins), which has no Intrinsics parameter to pass through. Threading one in
    // would touch those foreign files, so the realm's Intrinsics is instead recorded here at
    // install() time on an inheritable thread-local: async disposal runs on a Coroutine's own
    // virtual thread, and InheritableThreadLocal is what lets that thread see the value its parent
    // (ultimately the thread that ran install()) set before any coroutine existed.
    private static final ThreadLocal<Intrinsics> CURRENT_INTRINSICS = new InheritableThreadLocal<>();

    private ErrorBuiltins() {
    }

    public static JsObject makeError(String name, String message) {
        return makeError(name, message, null);
    }

    // An error instance's own message/name/cause are {w:true, e:false, c:true}, so Object.keys(err)
    // and JSON.stringify(err) report nothing while the values stay readable and replaceable.
    public static JsObject makeError(String name, String message, JsObject proto) {
        final var error = new JsObject();
        defineErrorProperty(error, "name", new JsString(name));
        defineErrorProperty(error, "message", new JsString(message));
        error.markErrorData();
        error.setProto(proto);
        return error;
    }

    public static void defineErrorProperty(JsObject error, String key, JsValue value) {
        error.defineValue(key, value);
        error.setFlags(key, ERROR_PROPERTY);
    }

    public static JsObject makeSuppressedError(JsValue error, JsValue suppressed, String message) {
        final var intrinsics = CURRENT_INTRINSICS.get();
        final var proto = intrinsics == null ? null : intrinsics.errorProto("SuppressedError");
        final var result = makeError("SuppressedError", message, proto);
        defineErrorProperty(result, "error", error);
        defineErrorProperty(result, "suppressed", suppressed);
        return result;
    }

    // `stack` is an accessor pair on Error.prototype, not an own data property of each instance: the
    // getter is brand-checked on [[ErrorData]] and the setter installs an own property on whatever
    // receiver it is handed (SetterThatIgnoresPrototypeProperties).
    public static void installStackAccessor(JsObject errorProto, InterpreterOps ops) {
        final var getter = new JsNativeFunction("get stack", (thisArg, _) -> stackOf(thisArg));
        getter.setLength(0);
        final var setter = new JsNativeFunction("set stack",
                (thisArg, args) -> setStack(errorProto, thisArg, arg(args, 0), ops));
        setter.setLength(1);
        errorProto.defineAccessor("stack", getter, setter);
        errorProto.setFlags("stack", new JsObject.PropertyFlags(true, false, true));
    }

    private static JsValue stackOf(JsValue thisArg) {
        if (!InterpreterUtils.isObjectLike(thisArg)) {
            throw new TypeErrorException("Error.prototype.stack getter called on a non-object");
        }
        if (!(thisArg instanceof JsObject error) || !error.isErrorData()) {
            return JsUndefined.getInstance();
        }
        // No interpreter call stack is retained, so the trace is a single synthetic frame.
        final var name = error.has("name") ? JsCoercion.toStr(error.get("name")) : "Error";
        final var message = error.has("message") ? JsCoercion.toStr(error.get("message")) : "";
        return new JsString(name + ": " + message + "\n    at <script>");
    }

    private static JsValue setStack(JsObject home, JsValue thisArg, JsValue value, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(thisArg)) {
            throw new TypeErrorException("Error.prototype.stack setter called on a non-object");
        }
        if (!(value instanceof JsString)) {
            throw new TypeErrorException("Error.prototype.stack setter requires a string");
        }
        // SetterThatIgnoresPrototypeProperties: assigning through the home object itself is the
        // spec's stand-in for writing a non-writable data property in strict code.
        if (thisArg == home) {
            throw new TypeErrorException("Cannot assign to read only property 'stack' of Error.prototype");
        }
        if (ops == null) {
            return JsUndefined.getInstance();
        }
        final var key = new JsString("stack");
        if (ops.getOwnPropertyDescriptor(thisArg, key) instanceof JsUndefined) {
            final var descriptor = new JsObject();
            descriptor.set("value", value);
            descriptor.set("writable", JsBoolean.of(true));
            descriptor.set("enumerable", JsBoolean.of(true));
            descriptor.set("configurable", JsBoolean.of(true));
            if (!ops.defineProperty(thisArg, key, descriptor)) {
                throw new TypeErrorException("Cannot create property 'stack' on the receiver");
            }
        } else if (!ops.setMember(thisArg, key, value)) {
            throw new TypeErrorException("Cannot assign to read only property 'stack' of the receiver");
        }
        return JsUndefined.getInstance();
    }

    public static void install(Environment global, Intrinsics intrinsics) {
        install(global, intrinsics, null, null);
    }

    public static void install(Environment global, Intrinsics intrinsics, InterpreterOps ops,
            IterableToList iterableToList) {
        CURRENT_INTRINSICS.set(intrinsics);
        JsNativeFunction errorConstructor = null;
        for (final var name : NAMES) {
            final var constructor = new JsNativeFunction(name, (_, args) -> construct(intrinsics, name, args, ops));
            if ("Error".equals(name)) {
                constructor.setProperty("isError",
                        new JsNativeFunction("isError", (_, args) -> JsBoolean.of(isError(arg(args, 0)))));
                errorConstructor = constructor;
            } else {
                // Each NativeError constructor's [[Prototype]] is %Error%, not %Function.prototype%.
                constructor.setOwnProto(errorConstructor);
            }
            constructor.setLength(1);
            link(constructor, intrinsics, name);
            global.declareBuiltin(name, constructor);
        }
        final var suppressed = new JsNativeFunction("SuppressedError",
                (_, args) -> constructSuppressed(intrinsics, args, ops));
        suppressed.setLength(3);
        suppressed.setOwnProto(errorConstructor);
        link(suppressed, intrinsics, "SuppressedError");
        global.declareBuiltin("SuppressedError", suppressed);
        final var aggregate = new JsNativeFunction("AggregateError",
                (_, args) -> constructAggregate(intrinsics, args, ops, iterableToList));
        aggregate.setLength(2);
        aggregate.setOwnProto(errorConstructor);
        link(aggregate, intrinsics, "AggregateError");
        global.declareBuiltin("AggregateError", aggregate);
    }

    private static JsObject construct(Intrinsics intrinsics, String name, List<JsValue> args, InterpreterOps ops) {
        final var error = newError(intrinsics, name, arg(args, 0), ops);
        installErrorCause(error, arg(args, 1), ops);
        return error;
    }

    private static JsObject constructSuppressed(Intrinsics intrinsics, List<JsValue> args, InterpreterOps ops) {
        final var error = newError(intrinsics, "SuppressedError", arg(args, 2), ops);
        defineErrorProperty(error, "error", arg(args, 0));
        defineErrorProperty(error, "suppressed", arg(args, 1));
        return error;
    }

    private static JsObject constructAggregate(Intrinsics intrinsics, List<JsValue> args, InterpreterOps ops,
            IterableToList iterableToList) {
        final var error = newError(intrinsics, "AggregateError", arg(args, 1), ops);
        installErrorCause(error, arg(args, 2), ops);
        final var array = new JsArray();
        for (final var element : errorsList(arg(args, 0), iterableToList)) {
            array.push(element);
        }
        defineErrorProperty(error, "errors", array);
        return error;
    }

    private static List<JsValue> errorsList(JsValue errors, IterableToList iterableToList) {
        if (iterableToList != null) {
            return iterableToList.drain(errors);
        }
        return errors instanceof JsArray array ? array.getElements() : List.of();
    }

    // The message own property exists only when the argument is not undefined; the prototype's
    // inherited empty string stands in otherwise.
    private static JsObject newError(Intrinsics intrinsics, String name, JsValue message, InterpreterOps ops) {
        final var error = new JsObject();
        error.markErrorData();
        error.setProto(newTargetProto(intrinsics, name, ops));
        if (!(message instanceof JsUndefined)) {
            defineErrorProperty(error, "message", new JsString(JsCoercion.toStr(message, ops)));
        }
        return error;
    }

    // OrdinaryCreateFromConstructor: Reflect.construct(Error, [], Other) links the instance to
    // Other.prototype rather than to the intrinsic error prototype.
    private static JsObject newTargetProto(Intrinsics intrinsics, String name, InterpreterOps ops) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (newTarget != null && ops != null
                && ops.getMember(newTarget, new JsString("prototype")) instanceof JsObject proto) {
            return proto;
        }
        return intrinsics.errorProto(name);
    }

    private static void installErrorCause(JsObject error, JsValue options, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(options)) {
            return;
        }
        final var key = new JsString("cause");
        if (ops == null) {
            if (options instanceof JsObject object && object.has(key.getValue())) {
                defineErrorProperty(error, "cause", object.get(key.getValue()));
            }
            return;
        }
        if (ops.has(options, key)) {
            defineErrorProperty(error, "cause", ops.getMember(options, key));
        }
    }

    private static void link(JsNativeFunction constructor, Intrinsics intrinsics, String name) {
        final var proto = intrinsics.errorProto(name);
        proto.defineValue("constructor", constructor);
        proto.setFlags("constructor", new JsObject.PropertyFlags(true, false, true));
        constructor.setPrototype(proto);
        constructor.markConstructor();
    }

    private static boolean isError(JsValue value) {
        return value instanceof JsObject object && object.isErrorData();
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
