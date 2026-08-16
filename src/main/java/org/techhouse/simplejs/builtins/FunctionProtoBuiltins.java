package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class FunctionProtoBuiltins {
    public static final List<String> NAMES = List.of("call", "apply", "bind", "toString");

    private FunctionProtoBuiltins() {
    }

    public static JsNativeFunction getMethod(JsValue target, String name, Invoker invoker, InterpreterOps ops) {
        return switch (name) {
            case "call" -> new JsNativeFunction("call", (_, args) -> call(target, args, invoker));
            case "apply" -> new JsNativeFunction("apply", (_, args) -> apply(target, args, invoker, ops));
            case "bind" -> new JsNativeFunction("bind", (_, args) -> bind(target, args, invoker, ops));
            case "toString" -> new JsNativeFunction("toString", (_, _) -> new JsString(sourceText(target)));
            default -> null;
        };
    }

    // No source text is retained for closures (the AST carries no offsets), so every callable
    // reports the NativeFunction shape the spec allows when HostHasSourceTextAvailable is false.
    // It has to parse as the NativeFunction production, so a synthesised name that is not an
    // IdentifierName (an anonymous function, `bound f`, a computed key) is dropped.
    public static String sourceText(JsValue target) {
        final var name = nameOf(target);
        final var accessor = name.startsWith("get ") || name.startsWith("set ");
        final var bare = accessor ? name.substring(4) : name;
        if (!isIdentifierName(bare)) {
            return "function () { [native code] }";
        }
        return "function " + (accessor ? name.substring(0, 4) : "") + bare + "() { [native code] }";
    }

    private static boolean isIdentifierName(String name) {
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
            return false;
        }
        for (var i = 0; i < name.length(); i++) {
            final var c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                return false;
            }
        }
        return true;
    }

    public static JsValue metadata(JsValue target, String key) {
        if ("name".equals(key)) {
            return new JsString(nameOf(target));
        }
        if ("length".equals(key)) {
            return new JsNumber(declaredLength(target));
        }
        return null;
    }

    private static int declaredLength(JsValue target) {
        if (target instanceof JsNativeFunction nf && nf.hasExplicitLength()) {
            return nf.getExplicitLength();
        }
        if (!(target instanceof JsFunction fn)) {
            return 0;
        }
        var count = 0;
        for (final var param : fn.getParams()) {
            if (!(param instanceof Identifier)) {
                break;
            }
            count++;
        }
        return count;
    }

    private static JsValue call(JsValue target, List<JsValue> args, Invoker invoker) {
        final var thisArg = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var rest = args.isEmpty() ? List.<JsValue>of() : args.subList(1, args.size());
        return invoker.call(target, thisArg, new ArrayList<>(rest));
    }

    private static JsValue apply(JsValue target, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var thisArg = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var argArray = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        return invoker.call(target, thisArg, createListFromArrayLike(argArray, ops));
    }

    // CreateListFromArrayLike: any object with a `length` works, not just a literal array; a
    // non-object (other than the elided/undefined/null argument) is a TypeError.
    private static List<JsValue> createListFromArrayLike(JsValue argArray, InterpreterOps ops) {
        if (argArray instanceof JsUndefined || argArray instanceof JsNull) {
            return new ArrayList<>();
        }
        if (argArray instanceof JsArray array) {
            return new ArrayList<>(array.getElements());
        }
        if (!InterpreterUtils.isObjectLike(argArray) || ops == null) {
            throw new TypeErrorException("CreateListFromArrayLike called on a non-object");
        }
        final var length = JsCoercion.toNumber(ops.getMember(argArray, new JsString("length")), ops);
        final var count = Double.isNaN(length) || length <= 0 ? 0 : (int) Math.min(length, Integer.MAX_VALUE);
        final var list = new ArrayList<JsValue>(count);
        for (var i = 0; i < count; i++) {
            list.add(ops.getMember(argArray, new JsString(String.valueOf(i))));
        }
        return list;
    }

    private static JsValue bind(JsValue target, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        if (!InterpreterUtils.isCallable(target)) {
            throw new TypeErrorException("Function.prototype.bind called on a non-callable");
        }
        final var boundThis = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var boundArgs = new ArrayList<>(args.isEmpty() ? List.of() : args.subList(1, args.size()));
        final var bound = new JsNativeFunction("bound " + targetName(target, ops), (_, callArgs) -> {
            final var combined = new ArrayList<>(boundArgs);
            combined.addAll(callArgs);
            return invoker.call(target, boundThis, combined);
        });
        bound.setLength(Math.max(0, targetLength(target, ops) - boundArgs.size()));
        bound.setBound(target, boundArgs);
        if (InterpreterUtils.isConstructor(target)) {
            bound.markConstructor();
        }
        return bound;
    }

    // Both come from Get(Target, ...) rather than the internal slots, so a script-installed own
    // `name`/`length` on the target is what the bound function inherits.
    private static String targetName(JsValue target, InterpreterOps ops) {
        if (ops == null) {
            return nameOf(target);
        }
        final var name = ops.getMember(target, new JsString("name"));
        return name instanceof JsString string ? string.getValue() : "";
    }

    private static int targetLength(JsValue target, InterpreterOps ops) {
        if (ops == null) {
            return declaredLength(target);
        }
        final var length = JsCoercion.toNumber(ops.getMember(target, new JsString("length")), ops);
        if (Double.isNaN(length) || length <= 0) {
            return 0;
        }
        return (int) Math.min(length, Integer.MAX_VALUE);
    }

    // OrdinaryHasInstance, reached through Function.prototype[Symbol.hasInstance]. The prototype
    // comes from Get(C, "prototype"), so an accessor-valued `prototype` runs and a non-object
    // result is a TypeError rather than a silent false.
    public static JsValue ordinaryHasInstance(JsValue target, JsValue value, InterpreterOps ops) {
        if (!InterpreterUtils.isCallable(target)) {
            return JsBoolean.FALSE;
        }
        if (target instanceof JsNativeFunction bound && bound.isBound()) {
            return ordinaryHasInstance(bound.getBoundTarget(), value, ops);
        }
        if (!InterpreterUtils.isObjectLike(value) || ops == null) {
            return JsBoolean.FALSE;
        }
        final var prototype = ops.getMember(target, new JsString("prototype"));
        if (!InterpreterUtils.isObjectLike(prototype)) {
            throw new TypeErrorException("Function has a non-object prototype in an instanceof check");
        }
        for (var current = ops.getPrototypeOf(value); InterpreterUtils
                .isObjectLike(current); current = ops.getPrototypeOf(current)) {
            if (current == prototype) {
                return JsBoolean.TRUE;
            }
        }
        return JsBoolean.FALSE;
    }

    private static String nameOf(JsValue target) {
        if (target instanceof JsFunction fn) {
            return fn.getName() == null ? "" : fn.getName();
        }
        if (target instanceof JsNativeFunction nf) {
            return nf.getName() == null ? "" : nf.getName();
        }
        if (target instanceof JsClass klass) {
            return klass.getName() == null ? "" : klass.getName();
        }
        return "";
    }
}
