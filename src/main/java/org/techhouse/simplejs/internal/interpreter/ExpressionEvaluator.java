package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.LOGICAL_ASSIGN;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.baseOperator;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.deleteArrayElement;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.numericOld;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.shouldNotApplyLogical;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.spreadObject;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.staticKeyName;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.stringCodePoints;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.ConditionalExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.SequenceExpression;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.nodes.TaggedTemplateExpression;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// Expression evaluation for the operator, assignment and literal-construction grammar: templates
// and tagged templates, array/object literals with spread and accessors, unary/binary/logical/
// conditional operators, update (++/--), typeof/delete, and assignment (plain, compound, logical,
// member and destructuring). All sub-evaluation and member access route through the Interpreter
// seam; instanceof/brand checks delegate to ClassEvaluator and delete to ProxyDispatch.
public final class ExpressionEvaluator {
    private final Interpreter interp;
    private final ClassEvaluator classes;
    private final ProxyDispatch proxies;

    public ExpressionEvaluator(Interpreter interp, ClassEvaluator classes, ProxyDispatch proxies) {
        this.interp = interp;
        this.classes = classes;
        this.proxies = proxies;
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
        final var strings = new JsArray();
        final var raw = new JsArray();
        for (final var cooked : quasi.getQuasis()) {
            strings.push(new JsString(cooked));
        }
        for (final var rawQuasi : quasi.getRawQuasis()) {
            raw.push(new JsString(rawQuasi));
        }
        raw.freeze();
        strings.setProperty("raw", raw);
        strings.freeze();
        final var args = new ArrayList<JsValue>();
        args.add(strings);
        for (final var expression : quasi.getExpressions()) {
            args.add(interp.eval(expression, env));
        }
        return interp.callValue(function, thisArg, args);
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

    public JsValue evalObject(ObjectExpression object, Environment env) {
        final var result = new JsObject();
        result.setProto(interp.intrinsics().objectProto());
        final var homeScope = env.child();
        homeScope.defineHomeClass(result);
        for (final var member : object.getProperties()) {
            if (member instanceof SpreadElement spread) {
                spreadObject(result, interp.eval(spread.getArgument(), env), interp.ops());
                continue;
            }
            if (!(member instanceof Property property)) {
                throw new UnsupportedNodeException(member.getType().name());
            }
            if (!(property.getValue() instanceof Expression value)) {
                throw new UnsupportedNodeException(property.getValue().getType().name());
            }
            final var accessor = "get".equals(property.getKind()) || "set".equals(property.getKind());
            // Only shorthand methods and accessors get a home object, so only they may use `super`
            final var scope = accessor || "method".equals(property.getKind()) ? homeScope : env;
            final var concise = accessor || "method".equals(property.getKind());
            if (property.isComputed()) {
                final var keyValue = interp.eval(property.getKey(), env);
                final var evaluated = markIfMethod(interp.eval(value, scope), concise);
                nameMember(property, value, evaluated,
                        keyValue instanceof JsSymbol symbol
                                ? ClassEvaluator.symbolMethodName(symbol)
                                : JsCoercion.toStr(keyValue));
                if (accessor && keyValue instanceof JsSymbol symbol) {
                    storeSymbolAccessor(result, symbol, property.getKind(), evaluated);
                } else if (accessor) {
                    storeAccessor(result, JsCoercion.toStr(keyValue), property.getKind(), evaluated);
                } else if (keyValue instanceof JsSymbol symbol) {
                    result.setSymbol(symbol, evaluated);
                    if (concise) {
                        result.setSymbolFlags(symbol, new JsObject.PropertyFlags(true, false, true));
                    }
                } else {
                    result.set(JsCoercion.toStr(keyValue), evaluated);
                }
                continue;
            }
            final var name = staticKeyName(property.getKey());
            final var evaluated = markIfMethod(interp.eval(value, scope), concise);
            // PropertyDefinitionEvaluation skips NamedEvaluation for the proto setter, so an
            // anonymous function assigned to `__proto__` is not named after the key.
            if (!accessor && "__proto__".equals(name)) {
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

    // A shorthand method or accessor is always an anonymous definition, so it is named
    // unconditionally; a plain `key: value` only takes the key when the value is one.
    private static void nameMember(Property property, Expression value, JsValue evaluated, String key) {
        final var kind = property.getKind();
        if ("method".equals(kind) || "get".equals(kind) || "set".equals(kind)) {
            InterpreterUtils.setFunctionName(evaluated, ClassEvaluator.accessorName(kind, key));
        } else {
            InterpreterUtils.applyInferredName(value, evaluated, key);
        }
    }

    private static JsValue markIfMethod(JsValue value, boolean concise) {
        if (concise && value instanceof JsFunction function) {
            function.markMethod();
        }
        return value;
    }

    // A non-computed `__proto__` in an object literal sets the prototype instead of creating a
    // property; per spec any value that is neither an object nor null is ignored.
    private static void setLiteralProto(JsObject target, JsValue value) {
        if (InterpreterUtils.isObjectLike(value)) {
            target.setProto(value);
        } else if (value instanceof JsNull) {
            target.setProto(null);
        }
    }

    public JsValue evalSequence(SequenceExpression sequence, Environment env) {
        var result = (JsValue) JsUndefined.getInstance();
        for (final var expression : sequence.getExpressions()) {
            result = interp.eval(expression, env);
        }
        return result;
    }

    private void storeAccessor(JsObject target, String key, String kind, JsValue fn) {
        if ("get".equals(kind)) {
            target.defineAccessor(key, fn, null);
        } else {
            target.defineAccessor(key, null, fn);
        }
    }

    private void storeSymbolAccessor(JsObject target, JsSymbol key, String kind, JsValue fn) {
        if ("get".equals(kind)) {
            target.defineSymbolAccessor(key, fn, null);
        } else {
            target.defineSymbolAccessor(key, null, fn);
        }
    }

    public JsValue evalUnary(UnaryExpression unary, Environment env) {
        final var operator = unary.getOperator();
        if ("typeof".equals(operator)) {
            return evalTypeof(unary.getArgument(), env);
        }
        if ("delete".equals(operator)) {
            return evalDelete(unary.getArgument(), env);
        }
        return JsOperators.unary(operator, interp.eval(unary.getArgument(), env), interp.ops());
    }

    private JsValue evalTypeof(Expression argument, Environment env) {
        if (argument instanceof Identifier id) {
            try {
                return new JsString(JsCoercion.typeOf(env.get(id.getName())));
            } catch (ReferenceErrorException ignored) {
                return new JsString("undefined");
            }
        }
        return new JsString(JsCoercion.typeOf(interp.eval(argument, env)));
    }

    private JsValue evalDelete(Expression argument, Environment env) {
        if (!(argument instanceof MemberExpression member)) {
            return JsBoolean.TRUE;
        }
        final var target = interp.eval(member.getObject(), env);
        final var keyValue = interp.memberKeyValue(member, env);
        if (keyValue instanceof JsSymbol symbol) {
            return deleteSymbolMember(target, symbol);
        }
        final var key = JsCoercion.toStr(keyValue, interp.ops());
        return switch (target) {
            case JsProxy proxy -> JsBoolean.of(proxies.delete(proxy, new JsString(key)));
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
            case JsCallableProperties callable -> {
                if (("name".equals(key) || "length".equals(key)) && !callable.hasProperty(key)) {
                    callable.markMetadataDeleted(key);
                    yield JsBoolean.TRUE;
                }
                yield JsBoolean.of(callable.deleteProperty(key));
            }
            default -> JsBoolean.TRUE;
        };
    }

    private JsValue deleteSymbolMember(JsValue target, JsSymbol symbol) {
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
            default -> JsBoolean.TRUE;
        };
    }

    public JsValue evalUpdate(UpdateExpression update, Environment env) {
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
            final var target = interp.eval(member.getObject(), env);
            final var key = interp.memberKeyValue(member, env);
            final var oldValue = interp.getMemberByKey(target, key);
            final var newValue = JsOperators.delta(oldValue, increment, interp.ops());
            assignMember(target, key, newValue);
            return update.isPrefix() ? newValue : numericOld(oldValue, interp.ops());
        }
        throw new UnsupportedNodeException(argument.getType().name());
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
        return JsBoolean.of(interp.hasMember(interp.eval(binary.getRight(), env), interp.eval(binary.getLeft(), env)));
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

    public JsValue evalAssignment(AssignmentExpression assignment, Environment env) {
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

    private JsValue assignToIdentifier(String name, AssignmentExpression assignment, Environment env) {
        final var operator = assignment.getOperator();
        if ("=".equals(operator)) {
            final var value = interp.eval(assignment.getValue(), env);
            InterpreterUtils.applyInferredName(assignment.getValue(), value, name);
            env.assign(name, value);
            return value;
        }
        final var current = env.get(name);
        if (LOGICAL_ASSIGN.contains(operator)) {
            if (shouldNotApplyLogical(operator, current)) {
                return current;
            }
            final var value = interp.eval(assignment.getValue(), env);
            InterpreterUtils.applyInferredName(assignment.getValue(), value, name);
            env.assign(name, value);
            return value;
        }
        final var value = JsOperators.binary(baseOperator(operator), current, interp.eval(assignment.getValue(), env),
                interp.ops());
        env.assign(name, value);
        return value;
    }

    private JsValue assignToMember(MemberExpression member, AssignmentExpression assignment, Environment env) {
        if (member.getProperty() instanceof PrivateIdentifier priv) {
            return interp.assignToPrivate(member, priv, assignment, env);
        }
        final var target = interp.eval(member.getObject(), env);
        final var key = interp.memberKeyValue(member, env);
        final var operator = assignment.getOperator();
        if ("=".equals(operator)) {
            final var value = interp.eval(assignment.getValue(), env);
            assignMember(target, key, value);
            return value;
        }
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

    // The engine is always strict, so a rejected write is a TypeError rather than a silent no-op
    private void assignMember(JsValue target, JsValue key, JsValue value) {
        if (!interp.setMemberByKey(target, key, value)) {
            throw new TypeErrorException(MemberEvaluator.writeRejectionMessage(target, key));
        }
    }
}
