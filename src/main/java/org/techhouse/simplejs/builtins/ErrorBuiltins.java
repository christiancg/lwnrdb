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

    private ErrorBuiltins() {
    }

    public static JsObject makeError(String name, String message) {
        return makeError(name, message, null);
    }

    public static JsObject makeError(String name, String message, JsObject proto) {
        final var error = new JsObject();
        error.set("name", new JsString(name));
        error.set("message", new JsString(message));
        error.markErrorData();
        error.setProto(proto);
        return error;
    }

    public static JsObject makeSuppressedError(JsValue error, JsValue suppressed, String message) {
        final var result = makeError("SuppressedError", message);
        result.set("error", error);
        result.set("suppressed", suppressed);
        return result;
    }

    public static JsObject makeAggregateError(List<JsValue> errors, String message) {
        final var result = makeError("AggregateError", message);
        final var array = new JsArray();
        for (final var error : errors) {
            array.push(error);
        }
        result.set("errors", array);
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
        if (thisArg == home || ops == null) {
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
        for (final var name : NAMES) {
            final var constructor = new JsNativeFunction(name, (_, args) -> construct(intrinsics, name, args));
            if ("Error".equals(name)) {
                constructor.setProperty("isError",
                        new JsNativeFunction("isError", (_, args) -> JsBoolean.of(isError(arg(args, 0)))));
            }
            link(constructor, intrinsics, name);
            global.declareBuiltin(name, constructor);
        }
        final var suppressed = new JsNativeFunction("SuppressedError", (_, args) -> link(
                makeSuppressedError(arg(args, 0), arg(args, 1), args.size() > 2 ? message(List.of(args.get(2))) : ""),
                intrinsics, "SuppressedError"));
        link(suppressed, intrinsics, "SuppressedError");
        global.declareBuiltin("SuppressedError", suppressed);
        final var aggregate = new JsNativeFunction("AggregateError", (_, args) -> {
            final var errors = arg(args, 0) instanceof JsArray array ? array.getElements() : List.<JsValue>of();
            return link(makeAggregateError(errors, args.size() > 1 ? message(List.of(args.get(1))) : ""), intrinsics,
                    "AggregateError");
        });
        link(aggregate, intrinsics, "AggregateError");
        global.declareBuiltin("AggregateError", aggregate);
    }

    private static JsObject construct(Intrinsics intrinsics, String name, List<JsValue> args) {
        final var error = intrinsics.makeError(name, message(args));
        if (args.size() > 1 && args.get(1) instanceof JsObject options && options.has("cause")) {
            error.set("cause", options.get("cause"));
        }
        return error;
    }

    private static JsObject link(JsObject error, Intrinsics intrinsics, String name) {
        error.setProto(intrinsics.errorProto(name));
        return error;
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

    private static String message(List<JsValue> args) {
        return args.isEmpty() ? "" : JsCoercion.toStr(args.getFirst());
    }
}
