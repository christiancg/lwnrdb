package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.LOGICAL_ASSIGN;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.baseOperator;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.numericOld;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.shouldNotApplyLogical;

import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.values.JsValue;

final class AssignmentEvaluator {
    private final Interpreter interp;
    private final ClassEvaluator classes;

    AssignmentEvaluator(Interpreter interp, ClassEvaluator classes) {
        this.interp = interp;
        this.classes = classes;
    }

    JsValue evalUpdate(UpdateExpression update, Environment env) {
        final var increment = "++".equals(update.getOperator());
        final var argument = update.getArgument();
        if (argument instanceof Identifier id) {
            final var oldValue = env.get(id.getName());
            final var newValue = JsOperators.delta(oldValue, increment, interp.ops());
            env.assign(id.getName(), newValue);
            return update.isPrefix() ? newValue : numericOld(oldValue, interp.ops());
        }
        if (argument instanceof MemberExpression member) {
            if (member.getProperty() instanceof PrivateIdentifier priv) {
                final var object = interp.eval(member.getObject(), env);
                final var oldValue = interp.getPrivateMember(object, priv.getName(), env);
                final var newValue = JsOperators.delta(oldValue, increment, interp.ops());
                interp.setPrivateMember(object, priv.getName(), newValue, env);
                return update.isPrefix() ? newValue : numericOld(oldValue, interp.ops());
            }
            if (member.getObject() instanceof SuperExpression) {
                final var oldValue = classes.evalSuperMemberRead(member, env);
                final var newValue = JsOperators.delta(oldValue, increment, interp.ops());
                classes.evalSuperMemberWrite(member, newValue, env);
                return update.isPrefix() ? newValue : numericOld(oldValue, interp.ops());
            }
            final var target = interp.eval(member.getObject(), env);
            final var key = interp.referenceKey(target, interp.memberKeyValue(member, env));
            final var oldValue = interp.getMemberByKey(target, key);
            final var newValue = JsOperators.delta(oldValue, increment, interp.ops());
            assignMember(target, key, newValue);
            return update.isPrefix() ? newValue : numericOld(oldValue, interp.ops());
        }
        throw new UnsupportedNodeException(argument.getType().name());
    }

    JsValue evalAssignment(AssignmentExpression assignment, Environment env) {
        final var target = assignment.getTarget();
        if (target instanceof Identifier id) {
            return assignToIdentifier(id.getName(), assignment, env);
        }
        if (target instanceof MemberExpression member) {
            return assignToMember(member, assignment, env);
        }
        if (target instanceof ArrayPattern || target instanceof ObjectPattern) {
            final var value = interp.eval(assignment.getValue(), env);
            interp.destructureAssignment(target, value, env);
            return value;
        }
        throw new UnsupportedNodeException(target.getType().name());
    }

    JsValue assignToIdentifier(String name, AssignmentExpression assignment, Environment env) {
        final var operator = assignment.getOperator();
        final var named = !assignment.isTargetParenthesized();
        if ("=".equals(operator)) {
            final var resolvable = env.isDeclared(name);
            final var value = named
                    ? interp.evalNamed(assignment.getValue(), env, name)
                    : interp.eval(assignment.getValue(), env);
            if (!resolvable) {
                throw new ReferenceErrorException(name + " is not defined");
            }
            env.assign(name, value);
            return value;
        }
        final var current = env.get(name);
        if (LOGICAL_ASSIGN.contains(operator)) {
            if (shouldNotApplyLogical(operator, current)) {
                return current;
            }
            final var value = named
                    ? interp.evalNamed(assignment.getValue(), env, name)
                    : interp.eval(assignment.getValue(), env);
            env.assign(name, value);
            return value;
        }
        final var value = JsOperators.binary(baseOperator(operator), current, interp.eval(assignment.getValue(), env),
                interp.ops());
        env.assign(name, value);
        return value;
    }

    JsValue assignToMember(MemberExpression member, AssignmentExpression assignment, Environment env) {
        if (member.getProperty() instanceof PrivateIdentifier priv) {
            return interp.assignToPrivate(member, priv, assignment, env);
        }
        if (member.getObject() instanceof SuperExpression) {
            return assignToSuperMember(member, assignment, env);
        }
        final var target = interp.eval(member.getObject(), env);
        final var rawKey = interp.memberKeyValue(member, env);
        final var operator = assignment.getOperator();
        if ("=".equals(operator)) {
            final var value = interp.eval(assignment.getValue(), env);
            assignMember(target, rawKey, value);
            return value;
        }
        final var key = interp.referenceKey(target, rawKey);
        final var current = interp.getMemberByKey(target, key);
        if (LOGICAL_ASSIGN.contains(operator)) {
            if (shouldNotApplyLogical(operator, current)) {
                return current;
            }
            final var value = interp.eval(assignment.getValue(), env);
            assignMember(target, key, value);
            return value;
        }
        final var value = JsOperators.binary(baseOperator(operator), current, interp.eval(assignment.getValue(), env),
                interp.ops());
        assignMember(target, key, value);
        return value;
    }

    JsValue assignToSuperMember(MemberExpression member, AssignmentExpression assignment, Environment env) {
        final var operator = assignment.getOperator();
        if ("=".equals(operator)) {
            return classes.evalSuperMemberAssign(member, assignment.getValue(), env);
        }
        final var current = classes.evalSuperMemberRead(member, env);
        if (LOGICAL_ASSIGN.contains(operator)) {
            if (shouldNotApplyLogical(operator, current)) {
                return current;
            }
            final var value = interp.eval(assignment.getValue(), env);
            classes.evalSuperMemberWrite(member, value, env);
            return value;
        }
        final var value = JsOperators.binary(baseOperator(operator), current, interp.eval(assignment.getValue(), env),
                interp.ops());
        classes.evalSuperMemberWrite(member, value, env);
        return value;
    }

    void assignMember(JsValue target, JsValue key, JsValue value) {
        if (!interp.setMemberByKey(target, key, value)) {
            throw new TypeErrorException(MemberEvaluator.writeRejectionMessage(target, key));
        }
    }
}
