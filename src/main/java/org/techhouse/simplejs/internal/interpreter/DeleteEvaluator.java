package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.deleteArrayElement;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsValue;

final class DeleteEvaluator {
    private final Interpreter interp;
    private final ProxyDispatch proxies;

    DeleteEvaluator(Interpreter interp, ProxyDispatch proxies) {
        this.interp = interp;
        this.proxies = proxies;
    }

    JsValue evalDelete(Expression argument, Environment env) {
        if (!(argument instanceof MemberExpression member)) {
            interp.eval(argument, env);
            return JsBoolean.TRUE;
        }
        if (member.getObject() instanceof SuperExpression) {
            env.resolveThis();
            interp.memberKeyValue(member, env);
            throw new ReferenceErrorException("Unsupported reference to 'super'");
        }
        final var target = interp.eval(member.getObject(), env);
        final var keyValue = interp.memberKeyValue(member, env);
        if (isNullish(target)) {
            if (member.isOptional()) {
                return JsBoolean.TRUE;
            }
            throw new TypeErrorException(
                    "Cannot convert undefined or null to object in a delete of " + JsCoercion.toStr(keyValue));
        }
        if (keyValue instanceof JsSymbol symbol) {
            return deleteSymbolMember(target, symbol);
        }
        final var key = JsCoercion.toStr(keyValue, interp.ops());
        return switch (target) {
            case JsProxy proxy -> {
                if (!proxies.delete(proxy, new JsString(key))) {
                    throw new TypeErrorException("Cannot delete property '" + key + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
            case JsObject object -> {
                if (!object.delete(key)) {
                    throw new TypeErrorException("Cannot delete property '" + key + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
            case JsClass cls -> {
                if (!cls.getStaticOwner().delete(key)) {
                    throw new TypeErrorException("Cannot delete property '" + key + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
            case JsArray array -> {
                if (!deleteArrayElement(array, key)) {
                    throw new TypeErrorException("Cannot delete property '" + key + "' of #<Array>");
                }
                yield JsBoolean.TRUE;
            }
            case JsArguments arguments -> {
                if (!arguments.deleteOwnProperty(new JsString(key))) {
                    throw new TypeErrorException("Cannot delete property '" + key + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
            case JsCallableProperties callable -> {
                if (("name".equals(key) || "length".equals(key)) && !callable.hasProperty(key)) {
                    callable.markMetadataDeleted(key);
                    yield JsBoolean.TRUE;
                }
                if ("prototype".equals(key) && !callable.hasProperty(key) && hasOwnPrototypeMetadata(callable)) {
                    throw new TypeErrorException("Cannot delete property 'prototype' of #<Function>");
                }
                yield JsBoolean.of(callable.deleteProperty(key));
            }
            default -> {
                if (!isObjectLike(target)) {
                    yield JsBoolean.TRUE;
                }
                if (!target.deleteOwnProperty(new JsString(key))) {
                    throw new TypeErrorException("Cannot delete property '" + key + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
        };
    }

    static boolean hasOwnPrototypeMetadata(JsCallableProperties callable) {
        if (callable instanceof JsFunction function) {
            return function.isConstructor() || function.isGenerator();
        }
        return callable instanceof JsNativeFunction nativeFunction && nativeFunction.getPrototype() != null;
    }

    JsValue deleteSymbolMember(JsValue target, JsSymbol symbol) {
        return switch (target) {
            case JsProxy proxy -> JsBoolean.of(proxies.delete(proxy, symbol));
            case JsObject object -> {
                if (object.isNotDeleteSymbol(symbol)) {
                    throw new TypeErrorException(
                            "Cannot delete property '" + symbol.getDescription() + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
            case JsClass cls -> {
                if (cls.getStaticOwner().isNotDeleteSymbol(symbol)) {
                    throw new TypeErrorException(
                            "Cannot delete property '" + symbol.getDescription() + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
            default -> JsBoolean.of(target.deleteOwnProperty(symbol));
        };
    }
}
