package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.LOGICAL_ASSIGN;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.baseOperator;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.deleteArrayElement;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.numericOld;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.shouldNotApplyLogical;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.staticKeyName;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.stringCodePoints;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.PropertyDescriptor;

// Expression evaluation for the operator, assignment and literal-construction grammar: templates
// and tagged templates, array/object literals with spread and accessors, unary/binary/logical/
// conditional operators, update (++/--), typeof/delete, and assignment (plain, compound, logical,
// member and destructuring). All sub-evaluation and member access route through the Interpreter
// seam; instanceof/brand checks delegate to ClassEvaluator and delete to ProxyDispatch.
public final class ExpressionEvaluator {
    private final Interpreter interp;
    private final ClassEvaluator classes;
    private final ProxyDispatch proxies;

    // GetTemplateObject's realm-scoped [[TemplateMap]]: one ExpressionEvaluator lives per
    // Interpreter (one per script run/realm), so keying by the TemplateLiteral parse node's
    // identity here reproduces "same Parse Node as templateLiteral" without needing state on the
    // node itself or on Interpreter.
    private final Map<TemplateLiteral, JsArray> templateCache = new IdentityHashMap<>();

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
        final var strings = templateCache.computeIfAbsent(quasi, this::buildTemplateStrings);
        final var args = new ArrayList<JsValue>();
        args.add(strings);
        for (final var expression : quasi.getExpressions()) {
            args.add(interp.eval(expression, env));
        }
        return interp.callValue(function, thisArg, args);
    }

    // GetTemplateObject's array construction: the "raw" companion is a non-enumerable,
    // non-writable, non-configurable own property (distinct from the cooked elements, which stay
    // enumerable per CreateArrayFromList before the whole object is frozen).
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

    public JsValue evalObject(ObjectExpression object, Environment env) {
        final var result = new JsObject();
        result.setProto(interp.intrinsics().objectProto());
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
            // Only shorthand methods and accessors get a home object, so only they may use `super`
            final var scope = accessor || "method".equals(property.getKind()) ? homeScope : env;
            final var concise = accessor || "method".equals(property.getKind());
            if (property.isComputed()) {
                // ToPropertyKey runs on the key as soon as it is evaluated (before the property's
                // own value), and must observe a user-defined toString/valueOf/Symbol.toPrimitive
                // rather than the data-only "[object Object]" fallback.
                final var keyValue = JsCoercion.toPropertyKey(interp.eval(property.getKey(), env), interp.ops());
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
            // The __proto__-setting special case is defined only for
            // `PropertyDefinition : PropertyName : AssignmentExpression` (the plain colon form) -
            // the shorthand (`{__proto__}`) and method (`{__proto__(){}}`) productions create an
            // ordinary own property named "__proto__" instead, so both are excluded here.
            // PropertyDefinitionEvaluation also skips NamedEvaluation for the proto setter, so an
            // anonymous function assigned to `__proto__` is not named after the key.
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

    // CopyDataProperties(target, source, []) - the property source for `...expr` in an object
    // literal: walk the source's real [[OwnPropertyKeys]]/[[GetOwnProperty]]/[[Get]] through the
    // ops seam (the same shape as Object.assign's source walk) instead of enumerating the source's
    // stored keys directly, so a Proxy source's trap order/values and a symbol-keyed accessor's
    // getter are observed exactly like a conformant engine. `undefined`/`null` is a documented no-op.
    private void copySpreadProperties(JsObject target, JsValue source) {
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

    // typeof only suppresses ReferenceError for an unresolvable reference (GetValue is never
    // called); a binding that exists but is still in its TDZ is resolvable, so GetValue - and its
    // ReferenceError - runs as normal.
    private JsValue evalTypeof(Expression argument, Environment env) {
        if (argument instanceof Identifier id) {
            if (!env.isDeclared(id.getName())) {
                // Not a declared var/function/lexical binding, but the Global Environment Record's
                // object-record half still answers via HasProperty/Get(globalObj, name) - reaches a
                // globalThis-only accessor (Object.defineProperty(globalThis, ...)) that never became
                // an Environment binding. See Interpreter.globalPropertyValue.
                final var globalValue = interp.globalPropertyValue(id.getName());
                return new JsString(globalValue == null ? "undefined" : typeOfValue(globalValue));
            }
            return new JsString(typeOfValue(env.get(id.getName())));
        }
        return new JsString(typeOfValue(interp.eval(argument, env)));
    }

    // Function.prototype is a plain JsObject, not a JsFunction/JsNativeFunction/JsClass, but
    // Interpreter.callValue special-cases it as callable (a spec-required no-op call) - so `typeof`
    // has to recognise the same special case rather than let JsCoercion.typeOf fall through to
    // "object" for it.
    private String typeOfValue(JsValue value) {
        if (value == interp.intrinsics().functionProto()) {
            return "function";
        }
        return JsCoercion.typeOf(value);
    }

    private JsValue evalDelete(Expression argument, Environment env) {
        if (!(argument instanceof MemberExpression member)) {
            // Deleting anything that is not a reference is still an evaluation: `delete f()` calls f.
            interp.eval(argument, env);
            return JsBoolean.TRUE;
        }
        // `delete super.x` is a super reference, and deleting one is a ReferenceError at runtime
        // rather than an early error, so the base and the key are evaluated first - but the base
        // (GetThisBinding) resolves before the key, so a still-uninitialised `this` in a derived
        // constructor throws before the key expression's side effects (e.g. the super() call that
        // would have initialised it) ever run.
        if (member.getObject() instanceof SuperExpression) {
            env.resolveThis();
            interp.memberKeyValue(member, env);
            throw new ReferenceErrorException("Unsupported reference to 'super'");
        }
        final var target = interp.eval(member.getObject(), env);
        final var keyValue = interp.memberKeyValue(member, env);
        // [[Delete]] runs on ToObject(base), so a nullish base is a TypeError - unless the access is
        // optional, where the whole chain short-circuits and the delete simply succeeds.
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
                // A callable's own "prototype" metadata (a constructor's or generator's linked
                // object, synthesised from a dedicated field rather than stored in the generic
                // property table - see OrdinaryProperties.hasPrototypeProperty/metadataDescriptor) is
                // always non-configurable, so its delete must be rejected instead of silently
                // succeeding because the table itself never held the key.
                if ("prototype".equals(key) && !callable.hasProperty(key) && hasOwnPrototypeMetadata(callable)) {
                    throw new TypeErrorException("Cannot delete property 'prototype' of #<Function>");
                }
                yield JsBoolean.of(callable.deleteProperty(key));
            }
            // Every remaining object-like type answers through its own [[Delete]] - notably the
            // global object, whose own string keys live in the Environment, so `delete globalThis.x`
            // has to remove the binding rather than report a success it never performed.
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

    // Mirrors OrdinaryProperties.hasPrototypeProperty (private there): a generator function owns a
    // "prototype" property despite not being a constructor, and a native function only has one when
    // JsNativeFunction.setPrototype was actually called for it (a plain arrow/non-constructor native
    // has none, so deleting its absent "prototype" is an ordinary no-op success).
    private static boolean hasOwnPrototypeMetadata(JsCallableProperties callable) {
        if (callable instanceof JsFunction function) {
            return function.isConstructor() || function.isGenerator();
        }
        return callable instanceof JsNativeFunction nativeFunction && nativeFunction.getPrototype() != null;
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
            // Every other exotic object keeps its symbol keys in the ordinary table an assignment
            // writes to, so the deletion has to reach the same place.
            default -> JsBoolean.of(target.deleteOwnProperty(symbol));
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

    // RelationalExpression: lref/lval are resolved before rref/rval, so the left operand's side
    // effects (an assignment, a sequence expression) are observable in the right operand.
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
        // NamedEvaluation only applies when the left-hand side is a bare IdentifierReference; a
        // parenthesized target (`(fn) = function(){}`) is a CoverParenthesizedExpression instead, so
        // the assigned anonymous function/class must keep its default (empty) name.
        final var named = !assignment.isTargetParenthesized();
        if ("=".equals(operator)) {
            // The target reference is resolved (ResolveBinding) before the right-hand side is
            // evaluated, so an assignment that becomes resolvable only as a side effect of
            // evaluating the right-hand side (`undeclared = (this.undeclared = 5)`) still throws:
            // PutValue acts on the resolution captured up front, not a re-resolution afterwards.
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

    private JsValue assignToMember(MemberExpression member, AssignmentExpression assignment, Environment env) {
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
            // A plain assignment never reads the reference, so ToPropertyKey waits for PutValue and
            // therefore runs after the right-hand side.
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

    private JsValue assignToSuperMember(MemberExpression member, AssignmentExpression assignment, Environment env) {
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

    // The engine is always strict, so a rejected write is a TypeError rather than a silent no-op
    private void assignMember(JsValue target, JsValue key, JsValue value) {
        if (!interp.setMemberByKey(target, key, value)) {
            throw new TypeErrorException(MemberEvaluator.writeRejectionMessage(target, key));
        }
    }
}
