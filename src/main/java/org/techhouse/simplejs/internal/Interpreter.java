package org.techhouse.simplejs.internal;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.BigIntLiteral;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.BooleanLiteral;
import org.techhouse.simplejs.nodes.BreakStatement;
import org.techhouse.simplejs.nodes.ConditionalExpression;
import org.techhouse.simplejs.nodes.ContinueStatement;
import org.techhouse.simplejs.nodes.DoWhileStatement;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.LabeledStatement;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
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
                    if (declarator.getId() instanceof Identifier id) {
                        if (LEXICAL_KINDS.contains(kind)) {
                            env.declareLexical(id.getName(), kind);
                        } else if ("var".equals(kind)) {
                            env.declareVar(id.getName());
                        }
                    }
                }
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
            case BREAK_STATEMENT -> Completion.breakOut(labelName(((BreakStatement) statement).getLabel()));
            case CONTINUE_STATEMENT -> Completion.continueOut(labelName(((ContinueStatement) statement).getLabel()));
            case RETURN_STATEMENT -> throw new SyntaxErrorException("Illegal return statement");
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
            if (!(declarator.getId() instanceof Identifier id)) {
                throw new UnsupportedNodeException(declarator.getId().getType().name());
            }
            final var name = id.getName();
            final var init = declarator.getInit();
            final var value = init == null ? JsUndefined.getInstance() : eval(init, env);
            if (LEXICAL_KINDS.contains(kind)) {
                env.initialize(name, value);
            } else if (init != null) {
                env.assign(name, value);
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
            case THIS_EXPRESSION -> JsUndefined.getInstance();
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
            } else if (element instanceof SpreadElement) {
                throw new UnsupportedNodeException(element.getType().name());
            } else {
                result.push(eval(element, env));
            }
        }
        return result;
    }

    private JsValue evalObject(ObjectExpression object, Environment env) {
        final var result = new JsObject();
        for (final var member : object.getProperties()) {
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
            return index == null ? JsUndefined.getInstance() : array.get(index);
        }
        if (target instanceof JsString string) {
            if ("length".equals(key)) {
                return new JsNumber(string.getValue().length());
            }
            final var index = arrayIndex(key);
            if (index != null && index < string.getValue().length()) {
                return new JsString(String.valueOf(string.getValue().charAt(index)));
            }
            return JsUndefined.getInstance();
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
}
