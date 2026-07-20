package org.techhouse.simplejs.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.techhouse.simplejs.builtins.ArrayBuiltins;
import org.techhouse.simplejs.builtins.ErrorBuiltins;
import org.techhouse.simplejs.builtins.GlobalScope;
import org.techhouse.simplejs.builtins.StringBuiltins;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.BigIntLiteral;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.BooleanLiteral;
import org.techhouse.simplejs.nodes.BreakStatement;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.CatchClause;
import org.techhouse.simplejs.nodes.ConditionalExpression;
import org.techhouse.simplejs.nodes.ContinueStatement;
import org.techhouse.simplejs.nodes.DoWhileStatement;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.LabeledStatement;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RestElement;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.ThrowStatement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class Interpreter {
    private enum LoopAction {
        CONTINUE_LOOP, BREAK_LOOP, PROPAGATE
    }

    @FunctionalInterface
    private interface LeafBinder {
        void bind(JsNode leaf, JsValue value, Environment env);
    }

    private static final Set<String> LOGICAL_ASSIGN = Set.of("&&=", "||=", "??=");
    private static final Set<String> LEXICAL_KINDS = Set.of("let", "const");

    private Interpreter() {
    }

    public static JsValue run(Program program) {
        return new Interpreter().evalProgram(program);
    }

    public static JsValue run(String source) {
        return run(Parser.parse(Lexer.lexWithPositions(source)));
    }

    private JsValue evalProgram(Program program) {
        final var env = Environment.global();
        GlobalScope.install(env);
        hoist(program.getBody(), env);
        var last = (JsValue) JsUndefined.getInstance();
        for (final var statement : program.getBody()) {
            final var completion = evalStatement(statement, env, null);
            if (!completion.isNormal()) {
                throw new SyntaxErrorException(
                        "Illegal " + completion.kind().name().toLowerCase(Locale.ROOT) + " statement");
            }
            last = completion.value();
        }
        return last;
    }

    private void hoist(List<Statement> body, Environment env) {
        for (final var statement : body) {
            if (statement instanceof VariableDeclaration declaration) {
                final var kind = declaration.getKind();
                for (final var declarator : declaration.getDeclarations()) {
                    final var names = new ArrayList<String>();
                    collectBoundNames(declarator.getId(), names);
                    for (final var name : names) {
                        if (LEXICAL_KINDS.contains(kind)) {
                            env.declareLexical(name, kind);
                        } else if ("var".equals(kind)) {
                            env.declareVar(name);
                        }
                    }
                }
            } else if (statement instanceof FunctionDeclaration declaration) {
                final var name = declaration.getName().getName();
                final var function = makeFunction(name, declaration.getParams(), declaration.getBody(), false, false,
                        env);
                env.declareFunction(name, function);
            }
        }
    }

    private Completion evalStatement(Statement statement, Environment env, String label) {
        return switch (statement.getType()) {
            case BLOCK_STATEMENT -> evalBlock((BlockStatement) statement, env);
            case EMPTY_STATEMENT -> Completion.empty();
            case EXPRESSION_STATEMENT ->
                Completion.normal(eval(((ExpressionStatement) statement).getExpression(), env));
            case VARIABLE_DECLARATION -> evalVariableDeclaration((VariableDeclaration) statement, env);
            case IF_STATEMENT -> evalIf((IfStatement) statement, env);
            case WHILE_STATEMENT -> evalWhile((WhileStatement) statement, env, label);
            case DO_WHILE_STATEMENT -> evalDoWhile((DoWhileStatement) statement, env, label);
            case FOR_STATEMENT -> evalFor((ForStatement) statement, env, label);
            case LABELED_STATEMENT -> evalLabeled((LabeledStatement) statement, env);
            case SWITCH_STATEMENT -> evalSwitch((SwitchStatement) statement, env, label);
            case BREAK_STATEMENT -> Completion.breakOut(labelName(((BreakStatement) statement).getLabel()));
            case CONTINUE_STATEMENT -> Completion.continueOut(labelName(((ContinueStatement) statement).getLabel()));
            case RETURN_STATEMENT -> evalReturn((ReturnStatement) statement, env);
            case THROW_STATEMENT -> throw new JsThrowException(eval(((ThrowStatement) statement).getArgument(), env));
            case TRY_STATEMENT -> evalTry((TryStatement) statement, env);
            case FUNCTION_DECLARATION -> Completion.empty();
            default -> throw new UnsupportedNodeException(statement.getType().name());
        };
    }

    private Completion evalBlock(BlockStatement block, Environment env) {
        final var blockEnv = env.child();
        hoist(block.getBody(), blockEnv);
        for (final var statement : block.getBody()) {
            final var completion = evalStatement(statement, blockEnv, null);
            if (!completion.isNormal()) {
                return completion;
            }
        }
        return Completion.empty();
    }

    private Completion evalVariableDeclaration(VariableDeclaration declaration, Environment env) {
        final var kind = declaration.getKind();
        if (!LEXICAL_KINDS.contains(kind) && !"var".equals(kind)) {
            throw new UnsupportedNodeException("VariableDeclaration kind '" + kind + "'");
        }
        for (final var declarator : declaration.getDeclarations()) {
            final var id = declarator.getId();
            final var init = declarator.getInit();
            if (id instanceof Identifier identifier) {
                final var name = identifier.getName();
                final var value = init == null ? JsUndefined.getInstance() : eval(init, env);
                if (LEXICAL_KINDS.contains(kind)) {
                    env.initialize(name, value);
                } else if (init != null) {
                    env.assign(name, value);
                }
            } else {
                final var value = init == null ? JsUndefined.getInstance() : eval(init, env);
                destructure(id, value, env, declarationLeaf(kind));
            }
        }
        return Completion.empty();
    }

    private Completion evalIf(IfStatement statement, Environment env) {
        if (JsCoercion.toBoolean(eval(statement.getTest(), env))) {
            return evalStatement(statement.getConsequent(), env, null);
        }
        if (statement.getAlternate() != null) {
            return evalStatement(statement.getAlternate(), env, null);
        }
        return Completion.empty();
    }

    private Completion evalWhile(WhileStatement statement, Environment env, String label) {
        while (JsCoercion.toBoolean(eval(statement.getTest(), env))) {
            final var completion = evalStatement(statement.getBody(), env, null);
            final var action = classify(completion, label);
            if (action == LoopAction.PROPAGATE) {
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                break;
            }
        }
        return Completion.empty();
    }

    private Completion evalDoWhile(DoWhileStatement statement, Environment env, String label) {
        do {
            final var completion = evalStatement(statement.getBody(), env, null);
            final var action = classify(completion, label);
            if (action == LoopAction.PROPAGATE) {
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                break;
            }
        } while (JsCoercion.toBoolean(eval(statement.getTest(), env)));
        return Completion.empty();
    }

    private Completion evalFor(ForStatement statement, Environment env, String label) {
        final var loopEnv = env.child();
        final var init = statement.getInit();
        if (init instanceof VariableDeclaration declaration) {
            hoist(List.of(declaration), loopEnv);
            evalVariableDeclaration(declaration, loopEnv);
        } else if (init instanceof Expression expression) {
            eval(expression, loopEnv);
        }
        while (statement.getTest() == null || JsCoercion.toBoolean(eval(statement.getTest(), loopEnv))) {
            final var completion = evalStatement(statement.getBody(), loopEnv, null);
            final var action = classify(completion, label);
            if (action == LoopAction.PROPAGATE) {
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                break;
            }
            if (statement.getUpdate() != null) {
                eval(statement.getUpdate(), loopEnv);
            }
        }
        return Completion.empty();
    }

    private Completion evalLabeled(LabeledStatement statement, Environment env) {
        final var label = statement.getLabel().getName();
        final var body = statement.getBody();
        final var completion = switch (body.getType()) {
            case WHILE_STATEMENT -> evalWhile((WhileStatement) body, env, label);
            case DO_WHILE_STATEMENT -> evalDoWhile((DoWhileStatement) body, env, label);
            case FOR_STATEMENT -> evalFor((ForStatement) body, env, label);
            case SWITCH_STATEMENT -> evalSwitch((SwitchStatement) body, env, label);
            default -> evalStatement(body, env, null);
        };
        if (completion.kind() == Completion.Kind.BREAK && label.equals(completion.label())) {
            return Completion.empty();
        }
        return completion;
    }

    private LoopAction classify(Completion completion, String label) {
        return switch (completion.kind()) {
            case NORMAL -> LoopAction.CONTINUE_LOOP;
            case CONTINUE -> matchesLabel(completion.label(), label) ? LoopAction.CONTINUE_LOOP : LoopAction.PROPAGATE;
            case BREAK -> matchesLabel(completion.label(), label) ? LoopAction.BREAK_LOOP : LoopAction.PROPAGATE;
            case RETURN -> LoopAction.PROPAGATE;
        };
    }

    private boolean matchesLabel(String completionLabel, String loopLabel) {
        return completionLabel == null || completionLabel.equals(loopLabel);
    }

    private String labelName(Identifier label) {
        return label == null ? null : label.getName();
    }

    private Completion evalReturn(ReturnStatement statement, Environment env) {
        final var argument = statement.getArgument();
        return Completion.returnValue(argument == null ? JsUndefined.getInstance() : eval(argument, env));
    }

    private Completion evalTry(TryStatement statement, Environment env) {
        Completion result = null;
        RuntimeException thrown = null;
        try {
            result = evalBlock(statement.getBlock(), env);
        } catch (JsThrowException | TypeErrorException | ReferenceErrorException | RangeErrorException
                | SyntaxErrorException error) {
            if (statement.getHandler() == null) {
                thrown = error;
            } else {
                try {
                    result = evalCatch(statement.getHandler(), toErrorValue(error), env);
                } catch (JsThrowException | TypeErrorException | ReferenceErrorException | RangeErrorException
                        | SyntaxErrorException nested) {
                    thrown = nested;
                }
            }
        }
        if (statement.getFinalizer() != null) {
            final var finalizer = evalBlock(statement.getFinalizer(), env);
            if (!finalizer.isNormal()) {
                return finalizer;
            }
        }
        if (thrown != null) {
            throw thrown;
        }
        return result;
    }

    private Completion evalCatch(CatchClause handler, JsValue error, Environment env) {
        final var catchEnv = env.child();
        final var param = handler.getParam();
        if (param instanceof Identifier id) {
            catchEnv.declareLexical(id.getName(), "let");
            catchEnv.initialize(id.getName(), error);
        } else if (param != null) {
            final var names = new ArrayList<String>();
            collectBoundNames(param, names);
            for (final var name : names) {
                catchEnv.declareLexical(name, "let");
            }
            destructure(param, error, catchEnv, declarationLeaf("let"));
        }
        return evalBlock(handler.getBody(), catchEnv);
    }

    private JsValue toErrorValue(RuntimeException error) {
        if (error instanceof JsThrowException thrown) {
            return thrown.getValue();
        }
        final var name = switch (error) {
            case TypeErrorException ignored -> "TypeError";
            case ReferenceErrorException ignored -> "ReferenceError";
            case RangeErrorException ignored -> "RangeError";
            default -> "SyntaxError";
        };
        return ErrorBuiltins.makeError(name, error.getMessage());
    }

    private Completion evalSwitch(SwitchStatement statement, Environment env, String label) {
        final var switchEnv = env.child();
        for (final var switchCase : statement.getCases()) {
            hoist(switchCase.getConsequent(), switchEnv);
        }
        final var discriminant = eval(statement.getDiscriminant(), switchEnv);
        final var cases = statement.getCases();
        var start = -1;
        var defaultIndex = -1;
        for (var i = 0; i < cases.size(); i++) {
            final var test = cases.get(i).getTest();
            if (test == null) {
                defaultIndex = i;
            } else if (JsOperators.strictEquals(discriminant, eval(test, switchEnv))) {
                start = i;
                break;
            }
        }
        if (start == -1) {
            start = defaultIndex;
        }
        if (start == -1) {
            return Completion.empty();
        }
        for (var i = start; i < cases.size(); i++) {
            for (final var consequent : cases.get(i).getConsequent()) {
                final var completion = evalStatement(consequent, switchEnv, null);
                if (completion.kind() == Completion.Kind.BREAK && matchesLabel(completion.label(), label)) {
                    return Completion.empty();
                }
                if (!completion.isNormal()) {
                    return completion;
                }
            }
        }
        return Completion.empty();
    }

    private JsValue eval(Expression expression, Environment env) {
        return switch (expression.getType()) {
            case NUMBER_LITERAL -> new JsNumber(((NumberLiteral) expression).getValue().doubleValue());
            case BIGINT_LITERAL -> new JsBigInt(((BigIntLiteral) expression).getValue());
            case STRING_LITERAL -> new JsString(((StringLiteral) expression).getValue());
            case BOOLEAN_LITERAL -> JsBoolean.of(((BooleanLiteral) expression).getValue());
            case NULL_LITERAL -> JsNull.getInstance();
            case UNDEFINED_LITERAL -> JsUndefined.getInstance();
            case TEMPLATE_LITERAL -> evalTemplate((TemplateLiteral) expression, env);
            case IDENTIFIER -> env.get(((Identifier) expression).getName());
            case THIS_EXPRESSION -> env.resolveThis();
            case FUNCTION_EXPRESSION -> evalFunctionExpression((FunctionExpression) expression, env);
            case ARROW_FUNCTION_EXPRESSION -> evalArrowFunction((ArrowFunctionExpression) expression, env);
            case CALL_EXPRESSION -> evalCall((CallExpression) expression, env);
            case NEW_EXPRESSION -> evalNew((NewExpression) expression, env);
            case ARRAY_EXPRESSION -> evalArray((ArrayExpression) expression, env);
            case OBJECT_EXPRESSION -> evalObject((ObjectExpression) expression, env);
            case UNARY_EXPRESSION -> evalUnary((UnaryExpression) expression, env);
            case UPDATE_EXPRESSION -> evalUpdate((UpdateExpression) expression, env);
            case BINARY_EXPRESSION -> evalBinary((BinaryExpression) expression, env);
            case LOGICAL_EXPRESSION -> evalLogical((LogicalExpression) expression, env);
            case ASSIGNMENT_EXPRESSION -> evalAssignment((AssignmentExpression) expression, env);
            case CONDITIONAL_EXPRESSION -> evalConditional((ConditionalExpression) expression, env);
            case MEMBER_EXPRESSION -> evalMember((MemberExpression) expression, env);
            default -> throw new UnsupportedNodeException(expression.getType().name());
        };
    }

    private JsValue evalTemplate(TemplateLiteral template, Environment env) {
        final var quasis = template.getQuasis();
        final var expressions = template.getExpressions();
        final var sb = new StringBuilder(quasis.getFirst());
        for (var i = 0; i < expressions.size(); i++) {
            sb.append(JsCoercion.toStr(eval(expressions.get(i), env)));
            sb.append(quasis.get(i + 1));
        }
        return new JsString(sb.toString());
    }

    private JsValue evalArray(ArrayExpression array, Environment env) {
        final var result = new JsArray();
        for (final var element : array.getElements()) {
            if (element == null) {
                result.push(JsUndefined.getInstance());
            } else if (element instanceof SpreadElement spread) {
                spreadInto(result.getElements(), eval(spread.getArgument(), env));
            } else {
                result.push(eval(element, env));
            }
        }
        return result;
    }

    private void spreadInto(List<JsValue> target, JsValue value) {
        if (value instanceof JsArray array) {
            target.addAll(array.getElements());
        } else if (value instanceof JsString string) {
            for (var i = 0; i < string.getValue().length(); i++) {
                target.add(new JsString(String.valueOf(string.getValue().charAt(i))));
            }
        } else {
            throw new TypeErrorException(JsCoercion.toStr(value) + " is not iterable");
        }
    }

    private void spreadObject(JsObject target, JsValue source) {
        if (source instanceof JsObject object) {
            for (final var entry : object.getProperties().entrySet()) {
                target.set(entry.getKey(), entry.getValue());
            }
        } else if (source instanceof JsArray array) {
            final var elements = array.getElements();
            for (var i = 0; i < elements.size(); i++) {
                target.set(Integer.toString(i), elements.get(i));
            }
        } else if (source instanceof JsString string) {
            for (var i = 0; i < string.getValue().length(); i++) {
                target.set(Integer.toString(i), new JsString(String.valueOf(string.getValue().charAt(i))));
            }
        }
    }

    private JsValue evalObject(ObjectExpression object, Environment env) {
        final var result = new JsObject();
        for (final var member : object.getProperties()) {
            if (member instanceof SpreadElement spread) {
                spreadObject(result, eval(spread.getArgument(), env));
                continue;
            }
            if (!(member instanceof Property property)) {
                throw new UnsupportedNodeException(member.getType().name());
            }
            final var key = property.isComputed()
                    ? JsCoercion.toStr(eval(property.getKey(), env))
                    : staticKeyName(property.getKey());
            if (!(property.getValue() instanceof Expression value)) {
                throw new UnsupportedNodeException(property.getValue().getType().name());
            }
            result.set(key, eval(value, env));
        }
        return result;
    }

    private String staticKeyName(Expression key) {
        return switch (key.getType()) {
            case IDENTIFIER -> ((Identifier) key).getName();
            case STRING_LITERAL -> ((StringLiteral) key).getValue();
            case NUMBER_LITERAL -> JsCoercion.toStr(new JsNumber(((NumberLiteral) key).getValue().doubleValue()));
            default -> throw new UnsupportedNodeException(key.getType().name());
        };
    }

    private JsValue evalUnary(UnaryExpression unary, Environment env) {
        final var operator = unary.getOperator();
        if ("typeof".equals(operator)) {
            return evalTypeof(unary.getArgument(), env);
        }
        if ("delete".equals(operator)) {
            return evalDelete(unary.getArgument(), env);
        }
        return JsOperators.unary(operator, eval(unary.getArgument(), env));
    }

    private JsValue evalTypeof(Expression argument, Environment env) {
        if (argument instanceof Identifier id) {
            try {
                return new JsString(JsCoercion.typeOf(env.get(id.getName())));
            } catch (ReferenceErrorException ignored) {
                return new JsString("undefined");
            }
        }
        return new JsString(JsCoercion.typeOf(eval(argument, env)));
    }

    private JsValue evalDelete(Expression argument, Environment env) {
        if (argument instanceof MemberExpression member) {
            final var target = eval(member.getObject(), env);
            final var key = memberKey(member, env);
            if (target instanceof JsObject object) {
                object.delete(key);
            } else if (target instanceof JsArray array) {
                final var index = arrayIndex(key);
                if (index != null && index < array.length()) {
                    array.set(index, JsUndefined.getInstance());
                }
            }
        }
        return JsBoolean.TRUE;
    }

    private JsValue evalUpdate(UpdateExpression update, Environment env) {
        final var increment = "++".equals(update.getOperator());
        final var argument = update.getArgument();
        if (argument instanceof Identifier id) {
            final var oldValue = env.get(id.getName());
            final var newValue = JsOperators.delta(oldValue, increment);
            env.assign(id.getName(), newValue);
            return update.isPrefix() ? newValue : numericOld(oldValue);
        }
        if (argument instanceof MemberExpression member) {
            final var target = eval(member.getObject(), env);
            final var key = memberKey(member, env);
            final var oldValue = getMember(target, key);
            final var newValue = JsOperators.delta(oldValue, increment);
            setMember(target, key, newValue);
            return update.isPrefix() ? newValue : numericOld(oldValue);
        }
        throw new UnsupportedNodeException(argument.getType().name());
    }

    private JsValue numericOld(JsValue oldValue) {
        if (oldValue instanceof JsBigInt) {
            return oldValue;
        }
        return new JsNumber(JsCoercion.toNumber(oldValue));
    }

    private JsValue evalBinary(BinaryExpression binary, Environment env) {
        final var operator = binary.getOperator();
        if ("instanceof".equals(operator)) {
            throw new UnsupportedNodeException("instanceof");
        }
        if ("in".equals(operator)) {
            return evalIn(binary, env);
        }
        return JsOperators.binary(operator, eval(binary.getLeft(), env), eval(binary.getRight(), env));
    }

    private JsValue evalIn(BinaryExpression binary, Environment env) {
        final var key = JsCoercion.toStr(eval(binary.getLeft(), env));
        final var container = eval(binary.getRight(), env);
        if (container instanceof JsObject object) {
            return JsBoolean.of(object.has(key));
        }
        if (container instanceof JsArray array) {
            if ("length".equals(key)) {
                return JsBoolean.TRUE;
            }
            final var index = arrayIndex(key);
            return JsBoolean.of(index != null && index < array.length());
        }
        throw new TypeErrorException("Cannot use 'in' operator to search for '" + key + "'");
    }

    private JsValue evalLogical(LogicalExpression logical, Environment env) {
        final var left = eval(logical.getLeft(), env);
        return switch (logical.getOperator()) {
            case "&&" -> JsCoercion.toBoolean(left) ? eval(logical.getRight(), env) : left;
            case "||" -> JsCoercion.toBoolean(left) ? left : eval(logical.getRight(), env);
            case "??" -> isNullish(left) ? eval(logical.getRight(), env) : left;
            default -> throw new TypeErrorException("Unknown logical operator: " + logical.getOperator());
        };
    }

    private JsValue evalConditional(ConditionalExpression conditional, Environment env) {
        if (JsCoercion.toBoolean(eval(conditional.getTest(), env))) {
            return eval(conditional.getConsequent(), env);
        }
        return eval(conditional.getAlternate(), env);
    }

    private JsValue evalAssignment(AssignmentExpression assignment, Environment env) {
        final var target = assignment.getTarget();
        if (target instanceof Identifier id) {
            return assignToIdentifier(id.getName(), assignment, env);
        }
        if (target instanceof MemberExpression member) {
            return assignToMember(member, assignment, env);
        }
        if (target instanceof ArrayPattern || target instanceof ObjectPattern) {
            final var value = eval(assignment.getValue(), env);
            destructure(target, value, env, assignmentLeaf());
            return value;
        }
        throw new UnsupportedNodeException(target.getType().name());
    }

    private JsValue assignToIdentifier(String name, AssignmentExpression assignment, Environment env) {
        final var operator = assignment.getOperator();
        if ("=".equals(operator)) {
            final var value = eval(assignment.getValue(), env);
            env.assign(name, value);
            return value;
        }
        final var current = env.get(name);
        if (LOGICAL_ASSIGN.contains(operator)) {
            if (shouldNotApplyLogical(operator, current)) {
                return current;
            }
            final var value = eval(assignment.getValue(), env);
            env.assign(name, value);
            return value;
        }
        final var value = JsOperators.binary(baseOperator(operator), current, eval(assignment.getValue(), env));
        env.assign(name, value);
        return value;
    }

    private JsValue assignToMember(MemberExpression member, AssignmentExpression assignment, Environment env) {
        final var target = eval(member.getObject(), env);
        final var key = memberKey(member, env);
        final var operator = assignment.getOperator();
        if ("=".equals(operator)) {
            final var value = eval(assignment.getValue(), env);
            setMember(target, key, value);
            return value;
        }
        final var current = getMember(target, key);
        if (LOGICAL_ASSIGN.contains(operator)) {
            if (shouldNotApplyLogical(operator, current)) {
                return current;
            }
            final var value = eval(assignment.getValue(), env);
            setMember(target, key, value);
            return value;
        }
        final var value = JsOperators.binary(baseOperator(operator), current, eval(assignment.getValue(), env));
        setMember(target, key, value);
        return value;
    }

    private boolean shouldNotApplyLogical(String operator, JsValue current) {
        return !switch (operator) {
            case "&&=" -> JsCoercion.toBoolean(current);
            case "||=" -> !JsCoercion.toBoolean(current);
            case "??=" -> isNullish(current);
            default -> throw new TypeErrorException("Unknown logical assignment: " + operator);
        };
    }

    private String baseOperator(String assignmentOperator) {
        return assignmentOperator.substring(0, assignmentOperator.length() - 1);
    }

    private JsValue evalMember(MemberExpression member, Environment env) {
        final var target = eval(member.getObject(), env);
        if (member.isOptional() && isNullish(target)) {
            return JsUndefined.getInstance();
        }
        final var key = memberKey(member, env);
        return getMember(target, key);
    }

    private String memberKey(MemberExpression member, Environment env) {
        if (member.isComputed()) {
            return JsCoercion.toStr(eval(member.getProperty(), env));
        }
        if (member.getProperty() instanceof Identifier id) {
            return id.getName();
        }
        throw new UnsupportedNodeException(member.getProperty().getType().name());
    }

    private JsValue getMember(JsValue target, String key) {
        if (target instanceof JsObject object) {
            return object.get(key);
        }
        if (target instanceof JsArray array) {
            if ("length".equals(key)) {
                return new JsNumber(array.length());
            }
            final var index = arrayIndex(key);
            if (index != null) {
                return array.get(index);
            }
            final var method = ArrayBuiltins.getMethod(array, key, this::callValue);
            return method == null ? JsUndefined.getInstance() : method;
        }
        if (target instanceof JsString string) {
            if ("length".equals(key)) {
                return new JsNumber(string.getValue().length());
            }
            final var index = arrayIndex(key);
            if (index != null) {
                return index < string.getValue().length()
                        ? new JsString(String.valueOf(string.getValue().charAt(index)))
                        : JsUndefined.getInstance();
            }
            final var method = StringBuiltins.getMethod(string, key);
            return method == null ? JsUndefined.getInstance() : method;
        }
        if (target instanceof JsNativeFunction fn && fn.hasProperty(key)) {
            return fn.getProperty(key);
        }
        if (isNullish(target)) {
            throw new TypeErrorException(
                    "Cannot read properties of " + JsCoercion.toStr(target) + " (reading '" + key + "')");
        }
        return JsUndefined.getInstance();
    }

    private void setMember(JsValue target, String key, JsValue value) {
        if (target instanceof JsObject object) {
            object.set(key, value);
            return;
        }
        if (target instanceof JsArray array) {
            final var index = arrayIndex(key);
            if (index != null) {
                array.set(index, value);
            }
            return;
        }
        if (isNullish(target)) {
            throw new TypeErrorException(
                    "Cannot set properties of " + JsCoercion.toStr(target) + " (setting '" + key + "')");
        }
    }

    private JsValue evalFunctionExpression(FunctionExpression expression, Environment env) {
        final var name = expression.getName() == null ? null : expression.getName().getName();
        return makeFunction(name, expression.getParams(), expression.getBody(), false, false, env);
    }

    private JsValue evalArrowFunction(ArrowFunctionExpression expression, Environment env) {
        return makeFunction(null, expression.getParams(), expression.getBody(), true, expression.isExpressionBody(),
                env);
    }

    private JsFunction makeFunction(String name, List<JsNode> params, JsNode body, boolean arrow,
            boolean expressionBody, Environment closure) {
        return new JsFunction(name, params, body, arrow, expressionBody, closure);
    }

    private JsValue evalCall(CallExpression call, Environment env) {
        final var callee = call.getCallee();
        var thisArg = (JsValue) JsUndefined.getInstance();
        final JsValue function;
        if (callee instanceof MemberExpression member) {
            final var object = eval(member.getObject(), env);
            if (member.isOptional() && isNullish(object)) {
                return JsUndefined.getInstance();
            }
            thisArg = object;
            function = getMember(object, memberKey(member, env));
        } else {
            function = eval(callee, env);
        }
        return callValue(function, thisArg, evalArguments(call.getArguments(), env));
    }

    private JsValue evalNew(NewExpression expression, Environment env) {
        final var callee = eval(expression.getCallee(), env);
        final var args = evalArguments(expression.getArguments(), env);
        if (callee instanceof JsNativeFunction nativeFunction) {
            return nativeFunction.invoke(JsUndefined.getInstance(), args);
        }
        if (callee instanceof JsFunction function && !function.isArrow()) {
            final var instance = new JsObject();
            final var result = callFunction(function, instance, args);
            return isObjectLike(result) ? result : instance;
        }
        throw new TypeErrorException(JsCoercion.toStr(callee) + " is not a constructor");
    }

    private boolean isObjectLike(JsValue value) {
        return value instanceof JsObject || value instanceof JsArray || value instanceof JsFunction
                || value instanceof JsNativeFunction;
    }

    private List<JsValue> evalArguments(List<Expression> arguments, Environment env) {
        final var values = new ArrayList<JsValue>();
        for (final var argument : arguments) {
            if (argument instanceof SpreadElement spread) {
                spreadInto(values, eval(spread.getArgument(), env));
            } else {
                values.add(eval(argument, env));
            }
        }
        return values;
    }

    private JsValue callValue(JsValue callee, JsValue thisArg, List<JsValue> args) {
        if (callee instanceof JsFunction function) {
            return callFunction(function, thisArg, args);
        }
        if (callee instanceof JsNativeFunction nativeFunction) {
            return nativeFunction.invoke(thisArg, args);
        }
        throw new TypeErrorException(JsCoercion.toStr(callee) + " is not a function");
    }

    private JsValue callFunction(JsFunction function, JsValue thisArg, List<JsValue> args) {
        final var activation = function.getClosure().functionChild();
        if (!function.isArrow()) {
            activation.defineThis(thisArg);
        }
        bindParams(function.getParams(), args, activation);
        if (function.isExpressionBody()) {
            return eval((Expression) function.getBody(), activation);
        }
        final var body = (BlockStatement) function.getBody();
        hoist(body.getBody(), activation);
        for (final var statement : body.getBody()) {
            final var completion = evalStatement(statement, activation, null);
            if (completion.kind() == Completion.Kind.RETURN) {
                return completion.value();
            }
            if (!completion.isNormal()) {
                break;
            }
        }
        return JsUndefined.getInstance();
    }

    private void bindParams(List<JsNode> params, List<JsValue> args, Environment activation) {
        for (var i = 0; i < params.size(); i++) {
            final var param = params.get(i);
            if (param instanceof RestElement rest) {
                final var restArray = new JsArray();
                for (var j = i; j < args.size(); j++) {
                    restArray.push(args.get(j));
                }
                declareParamNames(rest.getArgument(), activation);
                destructure(rest.getArgument(), restArray, activation, paramLeaf());
                return;
            }
            final var value = i < args.size() ? args.get(i) : JsUndefined.getInstance();
            if (param instanceof Identifier id) {
                activation.declareVar(id.getName());
                activation.assign(id.getName(), value);
            } else {
                declareParamNames(param, activation);
                destructure(param, value, activation, paramLeaf());
            }
        }
    }

    private void declareParamNames(JsNode param, Environment activation) {
        final var names = new ArrayList<String>();
        collectBoundNames(param, names);
        for (final var name : names) {
            activation.declareVar(name);
        }
    }

    private boolean isNullish(JsValue value) {
        return value instanceof JsNull || value instanceof JsUndefined;
    }

    private Integer arrayIndex(String key) {
        if (key.isEmpty()) {
            return null;
        }
        for (var i = 0; i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return null;
            }
        }
        if (key.length() > 1 && key.charAt(0) == '0') {
            return null;
        }
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void destructure(JsNode target, JsValue value, Environment env, LeafBinder leaf) {
        switch (target) {
            case AssignmentPattern pattern -> {
                final var resolved = value instanceof JsUndefined ? eval(pattern.getRight(), env) : value;
                destructure(pattern.getLeft(), resolved, env, leaf);
            }
            case ArrayPattern pattern -> destructureArray(pattern, value, env, leaf);
            case ObjectPattern pattern -> destructureObject(pattern, value, env, leaf);
            default -> leaf.bind(target, value, env);
        }
    }

    private void destructureArray(ArrayPattern pattern, JsValue value, Environment env, LeafBinder leaf) {
        final var elements = arrayLikeElements(value);
        final var patternElements = pattern.getElements();
        for (var i = 0; i < patternElements.size(); i++) {
            final var element = patternElements.get(i);
            if (element == null) {
                continue;
            }
            if (element instanceof RestElement rest) {
                final var restArray = new JsArray();
                for (var j = i; j < elements.size(); j++) {
                    restArray.push(elements.get(j));
                }
                destructure(rest.getArgument(), restArray, env, leaf);
                return;
            }
            final var elementValue = i < elements.size() ? elements.get(i) : JsUndefined.getInstance();
            destructure(element, elementValue, env, leaf);
        }
    }

    private void destructureObject(ObjectPattern pattern, JsValue value, Environment env, LeafBinder leaf) {
        if (isNullish(value)) {
            throw new TypeErrorException(
                    "Cannot destructure '" + JsCoercion.toStr(value) + "' as it is " + JsCoercion.toStr(value) + ".");
        }
        final var taken = new HashSet<String>();
        for (final var member : pattern.getProperties()) {
            if (member instanceof RestElement rest) {
                final var restObject = new JsObject();
                if (value instanceof JsObject object) {
                    for (final var entry : object.getProperties().entrySet()) {
                        if (!taken.contains(entry.getKey())) {
                            restObject.set(entry.getKey(), entry.getValue());
                        }
                    }
                }
                destructure(rest.getArgument(), restObject, env, leaf);
                return;
            }
            final var property = (Property) member;
            final var key = property.isComputed()
                    ? JsCoercion.toStr(eval(property.getKey(), env))
                    : staticKeyName(property.getKey());
            taken.add(key);
            destructure(property.getValue(), getMember(value, key), env, leaf);
        }
    }

    private List<JsValue> arrayLikeElements(JsValue value) {
        if (value instanceof JsArray array) {
            return array.getElements();
        }
        if (value instanceof JsString string) {
            final var chars = new ArrayList<JsValue>();
            for (var i = 0; i < string.getValue().length(); i++) {
                chars.add(new JsString(String.valueOf(string.getValue().charAt(i))));
            }
            return chars;
        }
        throw new TypeErrorException(JsCoercion.toStr(value) + " is not iterable");
    }

    private void collectBoundNames(JsNode target, List<String> names) {
        switch (target) {
            case Identifier id -> names.add(id.getName());
            case AssignmentPattern pattern -> collectBoundNames(pattern.getLeft(), names);
            case RestElement rest -> collectBoundNames(rest.getArgument(), names);
            case ArrayPattern pattern -> {
                for (final var element : pattern.getElements()) {
                    if (element != null) {
                        collectBoundNames(element, names);
                    }
                }
            }
            case ObjectPattern pattern -> {
                for (final var member : pattern.getProperties()) {
                    if (member instanceof RestElement rest) {
                        collectBoundNames(rest.getArgument(), names);
                    } else {
                        collectBoundNames(((Property) member).getValue(), names);
                    }
                }
            }
            default -> {
            }
        }
    }

    private LeafBinder declarationLeaf(String kind) {
        if (LEXICAL_KINDS.contains(kind)) {
            return (leaf, value, env) -> env.initialize(((Identifier) leaf).getName(), value);
        }
        return (leaf, value, env) -> env.assign(((Identifier) leaf).getName(), value);
    }

    private LeafBinder paramLeaf() {
        return (leaf, value, env) -> env.assign(((Identifier) leaf).getName(), value);
    }

    private LeafBinder assignmentLeaf() {
        return (leaf, value, env) -> {
            if (leaf instanceof Identifier id) {
                env.assign(id.getName(), value);
            } else if (leaf instanceof MemberExpression member) {
                setMember(eval(member.getObject(), env), memberKey(member, env), value);
            } else {
                throw new UnsupportedNodeException(leaf.getType().name());
            }
        };
    }
}
