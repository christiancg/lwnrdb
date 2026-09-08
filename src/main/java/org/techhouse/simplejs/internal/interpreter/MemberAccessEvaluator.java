package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.*;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public record MemberAccessEvaluator(Interpreter interp) {
    public JsValue evalMember(MemberExpression member, Environment env) {
        if (member.getObject() instanceof SuperExpression) {
            return interp.classes.evalSuperMemberRead(member, env);
        }
        final var target = evalChainObject(member.getObject(), env);
        if (target == Interpreter.SHORT_CIRCUIT) {
            return Interpreter.SHORT_CIRCUIT;
        }
        if (member.isOptional() && isNullish(target)) {
            return Interpreter.SHORT_CIRCUIT;
        }
        if (member.getProperty() instanceof PrivateIdentifier priv) {
            return interp.privateMembers.getPrivateMember(target, priv.getName(), env);
        }
        return interp.getMemberByKey(target, memberKeyValue(member, env));
    }

    public JsValue evalChainObject(Expression expression, Environment env) {
        return switch (expression) {
            case MemberExpression member -> evalMember(member, env);
            case CallExpression call -> interp.evalCall(call, env);
            default -> interp.eval(expression, env);
        };
    }

    public JsValue unwrapShortCircuit(JsValue value) {
        return value == Interpreter.SHORT_CIRCUIT ? JsUndefined.getInstance() : value;
    }

    public String memberKey(MemberExpression member, Environment env) {
        if (member.isComputed()) {
            return JsCoercion.toStr(interp.eval(member.getProperty(), env), interp.ops);
        }
        if (member.getProperty() instanceof Identifier id) {
            return id.getName();
        }
        throw new UnsupportedNodeException(member.getProperty().getType().name());
    }

    public JsValue memberKeyValue(MemberExpression member, Environment env) {
        if (member.isComputed()) {
            return interp.eval(member.getProperty(), env);
        }
        if (member.getProperty() instanceof Identifier id) {
            return new JsString(id.getName());
        }
        throw new UnsupportedNodeException(member.getProperty().getType().name());
    }

    public JsValue referenceKey(JsValue target, JsValue rawKey) {
        if (!(target instanceof JsProxy)) {
            requireObjectCoercible(target);
        }
        return JsCoercion.toPropertyKey(rawKey, interp.ops);
    }

    public void requireObjectCoercible(JsValue target) {
        if (isNullish(target)) {
            throw new TypeErrorException(
                    "Cannot read properties of " + JsCoercion.toStr(target) + " (reading a computed property)");
        }
    }

    public boolean setPrimitiveMember(JsValue target, String key, JsValue value, JsValue receiver) {
        final var keyValue = new JsString(key);
        for (JsValue link = interp.intrinsics.protoFor(target); link != null;) {
            if (link instanceof JsProxy proxy) {
                return interp.proxies.set(proxy, keyValue, value, receiver);
            }
            if (!(link instanceof JsObject object)) {
                return false;
            }
            if (object.hasAccessor(key)) {
                final var setter = object.getAccessorSetter(key);
                if (setter == null) {
                    return false;
                }
                interp.functions.callValue(setter, receiver, List.of(value));
                return true;
            }
            link = object.getProto();
        }
        return false;
    }

    public JsValue getStaticMember(JsClass cls, String key) {
        if ("prototype".equals(key)) {
            return cls.getPrototype();
        }
        if ("name".equals(key) && !cls.hasStaticProp(key)) {
            return new JsString(cls.getName() == null ? "" : cls.getName());
        }
        final var getter = cls.findStaticGetter(key);
        if (getter != null) {
            return interp.functions.callFunction(getter, cls, List.of());
        }
        final var method = cls.findStaticMethod(key);
        if (method != null) {
            return method;
        }
        for (var current = cls; current != null; current = current.getSuperClass()) {
            if (current.hasStaticProp(key)) {
                return current.getStaticProp(key);
            }
        }
        final var nativeSuper = cls.findNativeSuperClass();
        final JsValue target = nativeSuper != null ? nativeSuper : interp.intrinsics.functionProto;
        return interp.getMember(target, key);
    }

    public JsValue globalPropertyValue(String name) {
        if (interp.globalObjectValue == null) {
            return null;
        }
        final var descriptor = interp.globalObjectValue.getOwnProperty(new JsString(name));
        if (descriptor == null) {
            return null;
        }
        if (descriptor.isAccessorDescriptor()) {
            final var getter = descriptor.getter();
            return getter == null || getter instanceof JsUndefined
                    ? JsUndefined.getInstance()
                    : interp.functions.callValue(getter, interp.globalObjectValue, List.of());
        }
        return descriptor.value();
    }

    public List<JsValue> ownKeysOf(JsValue target) {
        return target instanceof JsProxy proxy
                ? interp.proxies.ownKeys(proxy)
                : new ArrayList<>(target.ownPropertyKeys());
    }

    public boolean deleteMemberValue(JsValue target, JsValue rawKey) {
        final var keyValue = JsCoercion.toPropertyKey(rawKey, interp.ops);
        if (target instanceof JsProxy proxy) {
            return interp.proxies.delete(proxy, keyValue);
        }
        if (target instanceof JsArray array && !(keyValue instanceof JsSymbol)) {
            return deleteArrayElement(array, JsCoercion.toStr(keyValue));
        }
        return target.deleteOwnProperty(keyValue);
    }

}
