package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.*;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsValue;

public record PrivateMemberEvaluator(Interpreter interp) {
    public JsValue getPrivateMember(JsValue target, String name, Environment env) {
        final var owner = env.resolvePrivateClass(name);
        final var privateName = owner == null ? null : owner.privateNameFor(name);
        if (owner != null && target == owner && owner.declaresStaticPrivate(privateName)) {
            final var getter = owner.getPrivateStaticGetter(privateName);
            if (getter != null) {
                return interp.callFunction(getter, owner, List.of());
            }
            final var method = owner.getPrivateStaticMethod(privateName);
            if (method != null) {
                return method;
            }
            if (owner.hasPrivateStaticField(privateName)) {
                return owner.getPrivateStaticField(privateName);
            }
        }
        final var object = owner == null ? null : interp.classes.resolvePrivateStorage(target);
        if (object != null) {
            if (object.hasPrivate(privateName)) {
                return object.getPrivate(privateName);
            }
            final var getter = owner.getPrivateInstanceGetter(privateName);
            final var method = owner.getPrivateInstanceMethod(privateName);
            requirePrivateBrand(object, owner, name, getter != null || method != null);
            if (getter != null) {
                return interp.callFunction(getter, target, List.of());
            }
            if (method != null) {
                return method;
            }
        }
        throw new TypeErrorException(
                "Cannot read private member #" + name + " from an object whose class did not declare it");
    }

    public void setPrivateMember(JsValue target, String name, JsValue value, Environment env) {
        final var owner = env.resolvePrivateClass(name);
        final var privateName = owner == null ? null : owner.privateNameFor(name);
        if (owner != null && target == owner && owner.declaresStaticPrivate(privateName)) {
            final var setter = owner.getPrivateStaticSetter(privateName);
            if (setter != null) {
                interp.callFunction(setter, owner, List.of(value));
                return;
            }
            if (owner.getPrivateStaticMethod(privateName) != null
                    || owner.getPrivateStaticGetter(privateName) != null) {
                throw new TypeErrorException("Cannot write to private member #" + name + ", it has no setter");
            }
            if (owner.hasPrivateStaticField(privateName)) {
                owner.setPrivateStaticField(privateName, value);
                return;
            }
        }
        final var object = owner == null ? null : interp.classes.resolvePrivateStorage(target);
        if (object != null) {
            final var setter = owner.getPrivateInstanceSetter(privateName);
            final var readOnly = owner.getPrivateInstanceGetter(privateName) != null
                    || owner.getPrivateInstanceMethod(privateName) != null;
            requirePrivateBrand(object, owner, name, setter != null || readOnly);
            if (setter != null) {
                interp.callFunction(setter, target, List.of(value));
                return;
            }
            if (readOnly) {
                throw new TypeErrorException("Cannot write to private member #" + name + ", it has no setter");
            }
            if (object.hasPrivate(privateName)) {
                object.setPrivate(privateName, value);
                return;
            }
        }
        throw new TypeErrorException(
                "Cannot write private member #" + name + " to an object whose class did not declare it");
    }

    public void requirePrivateBrand(JsObject object, JsClass cls, String name, boolean declared) {
        if (declared && !object.hasPrivateBrand(cls)) {
            throw new TypeErrorException(
                    "Cannot access private member #" + name + " from an object whose class did not declare it");
        }
    }

    public JsValue assignToPrivate(MemberExpression member, PrivateIdentifier priv, AssignmentExpression assignment,
            Environment env) {
        final var target = interp.eval(member.getObject(), env);
        final var name = priv.getName();
        final var operator = assignment.getOperator();
        if ("=".equals(operator)) {
            final var value = interp.eval(assignment.getValue(), env);
            setPrivateMember(target, name, value, env);
            return value;
        }
        final var current = getPrivateMember(target, name, env);
        if (LOGICAL_ASSIGN.contains(operator)) {
            if (shouldNotApplyLogical(operator, current)) {
                return current;
            }
            final var value = interp.eval(assignment.getValue(), env);
            setPrivateMember(target, name, value, env);
            return value;
        }
        final var value = JsOperators.binary(baseOperator(operator), current, interp.eval(assignment.getValue(), env),
                interp.ops);
        setPrivateMember(target, name, value, env);
        return value;
    }

}
