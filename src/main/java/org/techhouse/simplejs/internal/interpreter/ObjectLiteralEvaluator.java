package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.staticKeyName;

import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsValue;

final class ObjectLiteralEvaluator {
    private final Interpreter interp;

    ObjectLiteralEvaluator(Interpreter interp) {
        this.interp = interp;
    }

    JsValue evalObject(ObjectExpression object, Environment env) {
        final var result = new JsObject();
        result.setProto(interp.intrinsics().objectProto);
        final var homeScope = env.child();
        homeScope.defineHomeClass(result);
        for (final var member : object.getProperties()) {
            if (member instanceof SpreadElement spread) {
                copySpreadProperties(result, interp.eval(spread.getArgument(), env));
                continue;
            }
            if (!(member instanceof Property property)) {
                throw new UnsupportedNodeException(member.getType().name());
            }
            if (!(property.getValue() instanceof Expression value)) {
                throw new UnsupportedNodeException(property.getValue().getType().name());
            }
            final var accessor = "get".equals(property.getKind()) || "set".equals(property.getKind());
            final var scope = accessor || "method".equals(property.getKind()) ? homeScope : env;
            final var concise = accessor || "method".equals(property.getKind());
            if (property.isComputed()) {
                final var keyValue = JsCoercion.toPropertyKey(interp.eval(property.getKey(), env), interp.ops());
                final var evaluated = markIfMethod(interp.eval(value, scope), concise);
                nameMember(property, value, evaluated,
                        keyValue instanceof JsSymbol symbol
                                ? ClassMemberInstaller.symbolMethodName(symbol)
                                : JsCoercion.toStr(keyValue));
                if (accessor && keyValue instanceof JsSymbol symbol) {
                    storeSymbolAccessor(result, symbol, property.getKind(), evaluated);
                } else if (accessor) {
                    storeAccessor(result, JsCoercion.toStr(keyValue), property.getKind(), evaluated);
                } else if (keyValue instanceof JsSymbol symbol) {
                    result.setSymbol(symbol, evaluated);
                    if (concise) {
                        result.setSymbolFlags(symbol, JsObject.PropertyFlags.HIDDEN);
                    }
                } else {
                    result.set(JsCoercion.toStr(keyValue), evaluated);
                }
                continue;
            }
            final var name = staticKeyName(property.getKey());
            final var evaluated = markIfMethod(interp.eval(value, scope), concise);
            if (!accessor && "__proto__".equals(name) && "init".equals(property.getKind()) && !property.isShorthand()) {
                setLiteralProto(result, evaluated);
                continue;
            }
            nameMember(property, value, evaluated, name);
            if (accessor) {
                storeAccessor(result, name, property.getKind(), evaluated);
            } else {
                result.set(name, evaluated);
            }
        }
        return result;
    }

    static void nameMember(Property property, Expression value, JsValue evaluated, String key) {
        final var kind = property.getKind();
        if ("method".equals(kind) || "get".equals(kind) || "set".equals(kind)) {
            InterpreterUtils.setFunctionName(evaluated, ClassMemberInstaller.accessorName(kind, key));
        } else {
            InterpreterUtils.applyInferredName(value, evaluated, key);
        }
    }

    static JsValue markIfMethod(JsValue value, boolean concise) {
        if (concise && value instanceof JsFunction function) {
            function.markMethod();
        }
        return value;
    }

    void copySpreadProperties(JsObject target, JsValue source) {
        if (isNullish(source)) {
            return;
        }
        final var ops = interp.ops();
        final var from = interp.intrinsics().toObject(source);
        for (final var key : ops.ownKeys(from)) {
            if (!(ops.getOwnPropertyDescriptor(from, key) instanceof JsObject descriptor)
                    || !JsCoercion.toBoolean(descriptor.get("enumerable"))) {
                continue;
            }
            final var value = ops.getMember(from, key);
            if (key instanceof JsSymbol symbol) {
                target.setSymbol(symbol, value);
            } else {
                target.set(((JsString) key).getValue(), value);
            }
        }
    }

    static void setLiteralProto(JsObject target, JsValue value) {
        if (InterpreterUtils.isObjectLike(value)) {
            target.setProto(value);
        } else if (value instanceof JsNull) {
            target.setProto(null);
        }
    }

    void storeAccessor(JsObject target, String key, String kind, JsValue fn) {
        if ("get".equals(kind)) {
            target.defineAccessor(key, fn, null);
        } else {
            target.defineAccessor(key, null, fn);
        }
    }

    void storeSymbolAccessor(JsObject target, JsSymbol key, String kind, JsValue fn) {
        if ("get".equals(kind)) {
            target.defineSymbolAccessor(key, fn, null);
        } else {
            target.defineSymbolAccessor(key, null, fn);
        }
    }
}
