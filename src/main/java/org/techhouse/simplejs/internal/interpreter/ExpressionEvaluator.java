package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.stringCodePoints;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.ConditionalExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.SequenceExpression;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.nodes.TaggedTemplateExpression;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.PropertyDescriptor;

public final class ExpressionEvaluator {
    private final Interpreter interp;
    private final ClassEvaluator classes;

    private final Map<TemplateLiteral, JsArray> templateCache = new IdentityHashMap<>();

    private final ObjectLiteralEvaluator objects;

    private final DeleteEvaluator deletes;

    private final AssignmentEvaluator assignments;

    public ExpressionEvaluator(Interpreter interp, ClassEvaluator classes, ProxyDispatch proxies) {
        this.interp = interp;
        this.classes = classes;
        this.assignments = new AssignmentEvaluator(interp, classes);
        this.deletes = new DeleteEvaluator(interp, proxies);
        this.objects = new ObjectLiteralEvaluator(interp);
    }

    public JsValue evalTemplate(TemplateLiteral template, Environment env) {
        final var quasis = template.getQuasis();
        final var expressions = template.getExpressions();
        final var sb = new StringBuilder(quasis.getFirst());
        for (var i = 0; i < expressions.size(); i++) {
            sb.append(JsCoercion.toStr(interp.eval(expressions.get(i), env), interp.ops()));
            sb.append(quasis.get(i + 1));
        }
        return new JsString(sb.toString());
    }

    public JsValue evalTaggedTemplate(TaggedTemplateExpression tagged, Environment env) {
        final var tag = tagged.getTag();
        var thisArg = (JsValue) JsUndefined.getInstance();
        final JsValue function;
        if (tag instanceof MemberExpression member && !(member.getObject() instanceof SuperExpression)) {
            final var object = interp.eval(member.getObject(), env);
            if (member.isOptional() && isNullish(object)) {
                return JsUndefined.getInstance();
            }
            thisArg = object;
            if (member.getProperty() instanceof PrivateIdentifier priv) {
                function = interp.getPrivateMember(object, priv.getName(), env);
            } else {
                function = interp.getMemberByKey(object, interp.memberKeyValue(member, env));
            }
        } else {
            function = interp.eval(tag, env);
        }
        final var quasi = tagged.getQuasi();
        final var strings = templateCache.computeIfAbsent(quasi, this::buildTemplateStrings);
        final var args = new ArrayList<JsValue>();
        args.add(strings);
        for (final var expression : quasi.getExpressions()) {
            args.add(interp.eval(expression, env));
        }
        return interp.callValue(function, thisArg, args);
    }

    private JsArray buildTemplateStrings(TemplateLiteral quasi) {
        final var strings = new JsArray();
        final var raw = new JsArray();
        for (final var cooked : quasi.getQuasis()) {
            strings.push(cooked == null ? JsUndefined.getInstance() : new JsString(cooked));
        }
        for (final var rawQuasi : quasi.getRawQuasis()) {
            raw.push(new JsString(rawQuasi));
        }
        raw.freeze();
        strings.defineOwnProperty(new JsString("raw"),
                PropertyDescriptor.data(raw, new JsObject.PropertyFlags(false, false, false)));
        strings.freeze();
        return strings;
    }

    public JsValue evalArray(ArrayExpression array, Environment env) {
        final var result = new JsArray();
        for (final var element : array.getElements()) {
            if (element == null) {
                result.pushHole();
            } else if (element instanceof SpreadElement spread) {
                spreadInto(result.getElements(), interp.eval(spread.getArgument(), env));
            } else {
                result.push(interp.eval(element, env));
            }
        }
        return result;
    }

    public void spreadInto(List<JsValue> target, JsValue value) {
        switch (value) {
            case JsArray array when Iteration.usesDefaultIterator(interp, array) -> {
                for (var i = 0; i < array.length(); i++) {
                    target.add(array.isHole(i) ? JsUndefined.getInstance() : array.get(i));
                }
            }
            case JsString string when Iteration.usesDefaultIterator(interp, string) ->
                target.addAll(stringCodePoints(string.getValue()));
            default -> {
                final var iteration = new Iteration(interp, value);
                var element = iteration.next();
                while (element != null) {
                    target.add(element);
                    element = iteration.next();
                }
            }
        }
    }

    public JsValue evalSequence(SequenceExpression sequence, Environment env) {
        var result = (JsValue) JsUndefined.getInstance();
        for (final var expression : sequence.getExpressions()) {
            result = interp.eval(expression, env);
        }
        return result;
    }

    public JsValue evalUnary(UnaryExpression unary, Environment env) {
        final var operator = unary.getOperator();
        if ("typeof".equals(operator)) {
            return evalTypeof(unary.getArgument(), env);
        }
        if ("delete".equals(operator)) {
            return deletes.evalDelete(unary.getArgument(), env);
        }
        return JsOperators.unary(operator, interp.eval(unary.getArgument(), env), interp.ops());
    }

    private JsValue evalTypeof(Expression argument, Environment env) {
        if (argument instanceof Identifier id) {
            if (!env.isDeclared(id.getName())) {
                final var globalValue = interp.globalPropertyValue(id.getName());
                return new JsString(globalValue == null ? "undefined" : typeOfValue(globalValue));
            }
            return new JsString(typeOfValue(env.get(id.getName())));
        }
        return new JsString(typeOfValue(interp.eval(argument, env)));
    }

    private String typeOfValue(JsValue value) {
        if (value == interp.intrinsics().functionProto) {
            return "function";
        }
        return JsCoercion.typeOf(value);
    }

    public JsValue evalBinary(BinaryExpression binary, Environment env) {
        final var operator = binary.getOperator();
        if ("instanceof".equals(operator)) {
            return classes.evalInstanceof(interp.eval(binary.getLeft(), env), interp.eval(binary.getRight(), env));
        }
        if ("in".equals(operator)) {
            if (binary.getLeft() instanceof PrivateIdentifier priv) {
                return classes.evalBrandCheck(priv, interp.eval(binary.getRight(), env), env);
            }
            return evalIn(binary, env);
        }
        return JsOperators.binary(operator, interp.eval(binary.getLeft(), env), interp.eval(binary.getRight(), env),
                interp.ops());
    }

    private JsValue evalIn(BinaryExpression binary, Environment env) {
        final var key = interp.eval(binary.getLeft(), env);
        final var target = interp.eval(binary.getRight(), env);
        return JsBoolean.of(interp.hasMember(target, key));
    }

    public JsValue evalLogical(LogicalExpression logical, Environment env) {
        final var left = interp.eval(logical.getLeft(), env);
        return switch (logical.getOperator()) {
            case "&&" -> JsCoercion.toBoolean(left) ? interp.eval(logical.getRight(), env) : left;
            case "||" -> JsCoercion.toBoolean(left) ? left : interp.eval(logical.getRight(), env);
            case "??" -> isNullish(left) ? interp.eval(logical.getRight(), env) : left;
            default -> throw new TypeErrorException("Unknown logical operator: " + logical.getOperator());
        };
    }

    public JsValue evalConditional(ConditionalExpression conditional, Environment env) {
        if (JsCoercion.toBoolean(interp.eval(conditional.getTest(), env))) {
            return interp.eval(conditional.getConsequent(), env);
        }
        return interp.eval(conditional.getAlternate(), env);
    }
    public JsValue evalObject(ObjectExpression object, Environment env) {
        return objects.evalObject(object, env);
    }

    public JsValue evalUpdate(UpdateExpression update, Environment env) {
        return assignments.evalUpdate(update, env);
    }

    public JsValue evalAssignment(AssignmentExpression assignment, Environment env) {
        return assignments.evalAssignment(assignment, env);
    }
}
