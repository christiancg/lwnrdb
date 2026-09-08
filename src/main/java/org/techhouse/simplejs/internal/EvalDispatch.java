package org.techhouse.simplejs.internal;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.*;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.AwaitExpression;
import org.techhouse.simplejs.nodes.BigIntLiteral;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.BooleanLiteral;
import org.techhouse.simplejs.nodes.BreakStatement;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ClassExpression;
import org.techhouse.simplejs.nodes.ConditionalExpression;
import org.techhouse.simplejs.nodes.ContinueStatement;
import org.techhouse.simplejs.nodes.DoWhileStatement;
import org.techhouse.simplejs.nodes.ExportDefaultDeclaration;
import org.techhouse.simplejs.nodes.ExportNamedDeclaration;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.ForInStatement;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.ImportDeclaration;
import org.techhouse.simplejs.nodes.ImportExpression;
import org.techhouse.simplejs.nodes.LabeledStatement;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.MetaProperty;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.RegexLiteral;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.SequenceExpression;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.TaggedTemplateExpression;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.ThrowStatement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;
import org.techhouse.simplejs.nodes.YieldExpression;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

final class EvalDispatch {
    private final Interpreter interpreter;

    EvalDispatch(Interpreter interpreter) {
        this.interpreter = interpreter;
    }

    void hoist(List<Statement> body, Environment env) {
        for (final var raw : body) {
            final var statement = raw instanceof ExportNamedDeclaration export
                    && export.getDeclaration() instanceof Statement inner ? inner : raw;
            if (statement instanceof VariableDeclaration declaration) {
                final var kind = declaration.getKind();
                for (final var declarator : declaration.getDeclarations()) {
                    final var names = new ArrayList<String>();
                    collectBoundNames(declarator.getId(), names);
                    for (final var name : names) {
                        if (LEXICAL_KINDS.contains(kind)) {
                            env.declareLexical(name, kind);
                        } else if (USING_KINDS.contains(kind)) {
                            env.declareLexical(name, "const");
                        } else if ("var".equals(kind)) {
                            env.declareVar(name);
                        }
                    }
                }
            } else if (statement instanceof FunctionDeclaration declaration) {
                final var name = declaration.getName().getName();
                final var function = interpreter.functionFactory.makeFunction(name, declaration.getParams(),
                        declaration.getBody(), false, false, declaration.isAsync(), declaration.isGenerator(), env,
                        declaration.getSourceText());
                env.declareFunction(name, function);
            }
        }
    }

    Completion evalStatement(Statement statement, Environment env) {
        interpreter.callStack.setPosition(statement.getPosition());
        return switch (statement.getType()) {
            case BLOCK_STATEMENT -> interpreter.statements.evalBlock((BlockStatement) statement, env);
            case EMPTY_STATEMENT -> Completion.empty();
            case EXPRESSION_STATEMENT ->
                Completion.normal(eval(((ExpressionStatement) statement).getExpression(), env));
            case VARIABLE_DECLARATION -> interpreter.evalVariableDeclaration((VariableDeclaration) statement, env);
            case IF_STATEMENT -> interpreter.statements.evalIf((IfStatement) statement, env);
            case WHILE_STATEMENT -> interpreter.statements.evalWhile((WhileStatement) statement, env, null);
            case DO_WHILE_STATEMENT -> interpreter.statements.evalDoWhile((DoWhileStatement) statement, env, null);
            case FOR_STATEMENT -> interpreter.statements.evalFor((ForStatement) statement, env, null);
            case FOR_IN_STATEMENT -> interpreter.statements.evalForIn((ForInStatement) statement, env, null);
            case FOR_OF_STATEMENT -> interpreter.statements.evalForOf((ForOfStatement) statement, env, null);
            case LABELED_STATEMENT -> interpreter.statements.evalLabeled((LabeledStatement) statement, env);
            case SWITCH_STATEMENT -> interpreter.statements.evalSwitch((SwitchStatement) statement, env, null);
            case BREAK_STATEMENT -> Completion.breakOut(labelName(((BreakStatement) statement).getLabel()));
            case CONTINUE_STATEMENT -> Completion.continueOut(labelName(((ContinueStatement) statement).getLabel()));
            case RETURN_STATEMENT -> interpreter.statements.evalReturn((ReturnStatement) statement, env);
            case THROW_STATEMENT -> throw new JsThrowException(eval(((ThrowStatement) statement).getArgument(), env));
            case TRY_STATEMENT -> interpreter.statements.evalTry((TryStatement) statement, env);
            case CLASS_DECLARATION -> interpreter.classes.evalClassDeclaration((ClassDeclaration) statement, env);
            case FUNCTION_DECLARATION -> Completion.empty();
            case IMPORT_DECLARATION -> {
                interpreter.modules.bindImport((ImportDeclaration) statement, env);
                yield Completion.empty();
            }
            case EXPORT_NAMED_DECLARATION -> {
                final var declaration = ((ExportNamedDeclaration) statement).getDeclaration();
                yield declaration instanceof Statement inner ? evalStatement(inner, env) : Completion.empty();
            }
            case EXPORT_DEFAULT_DECLARATION -> {
                interpreter.modules.evalExportDefault((ExportDefaultDeclaration) statement, env);
                yield Completion.empty();
            }
            case EXPORT_ALL_DECLARATION -> Completion.empty();
            default -> throw new UnsupportedNodeException(statement.getType().name());
        };
    }

    JsValue evalNamed(Expression expression, Environment env, String name) {
        if (name != null && expression instanceof ClassExpression classExpr && classExpr.getId() == null) {
            return interpreter.classes.evalClassExpression(classExpr, env, name);
        }
        final var value = eval(expression, env);
        applyInferredName(expression, value, name);
        return value;
    }

    JsValue eval(Expression expression, Environment env) {
        return switch (expression.getType()) {
            case NUMBER_LITERAL -> new JsNumber(((NumberLiteral) expression).getValue().doubleValue());
            case BIGINT_LITERAL -> new JsBigInt(((BigIntLiteral) expression).getValue());
            case STRING_LITERAL -> new JsString(((StringLiteral) expression).getValue());
            case BOOLEAN_LITERAL -> JsBoolean.of(((BooleanLiteral) expression).getValue());
            case NULL_LITERAL -> JsNull.getInstance();
            case UNDEFINED_LITERAL -> JsUndefined.getInstance();
            case REGEX_LITERAL -> RegexTranslator.compile(((RegexLiteral) expression).getPattern(),
                    ((RegexLiteral) expression).getFlags());
            case TEMPLATE_LITERAL -> interpreter.expressions.evalTemplate((TemplateLiteral) expression, env);
            case TAGGED_TEMPLATE_EXPRESSION ->
                interpreter.expressions.evalTaggedTemplate((TaggedTemplateExpression) expression, env);
            case IDENTIFIER -> env.get(((Identifier) expression).getName());
            case THIS_EXPRESSION -> env.resolveThis();
            case FUNCTION_EXPRESSION ->
                interpreter.functionFactory.evalFunctionExpression((FunctionExpression) expression, env);
            case ARROW_FUNCTION_EXPRESSION ->
                interpreter.functionFactory.evalArrowFunction((ArrowFunctionExpression) expression, env);
            case CALL_EXPRESSION -> interpreter.memberAccess
                    .unwrapShortCircuit(interpreter.functionFactory.evalCall((CallExpression) expression, env));
            case NEW_EXPRESSION -> interpreter.constructs.evalNew((NewExpression) expression, env);
            case ARRAY_EXPRESSION -> interpreter.expressions.evalArray((ArrayExpression) expression, env);
            case OBJECT_EXPRESSION -> interpreter.expressions.evalObject((ObjectExpression) expression, env);
            case UNARY_EXPRESSION -> interpreter.expressions.evalUnary((UnaryExpression) expression, env);
            case UPDATE_EXPRESSION -> interpreter.expressions.evalUpdate((UpdateExpression) expression, env);
            case BINARY_EXPRESSION -> interpreter.expressions.evalBinary((BinaryExpression) expression, env);
            case LOGICAL_EXPRESSION -> interpreter.expressions.evalLogical((LogicalExpression) expression, env);
            case ASSIGNMENT_EXPRESSION ->
                interpreter.expressions.evalAssignment((AssignmentExpression) expression, env);
            case CONDITIONAL_EXPRESSION ->
                interpreter.expressions.evalConditional((ConditionalExpression) expression, env);
            case SEQUENCE_EXPRESSION -> interpreter.expressions.evalSequence((SequenceExpression) expression, env);
            case MEMBER_EXPRESSION -> interpreter.memberAccess
                    .unwrapShortCircuit(interpreter.memberAccess.evalMember((MemberExpression) expression, env));
            case CLASS_EXPRESSION -> interpreter.classes.evalClassExpression((ClassExpression) expression, env);
            case YIELD_EXPRESSION -> interpreter.functions.evalYield((YieldExpression) expression, env);
            case AWAIT_EXPRESSION -> interpreter.functions.evalAwait((AwaitExpression) expression, env);
            case IMPORT_EXPRESSION -> interpreter.modules.evalImportExpression((ImportExpression) expression, env);
            case META_PROPERTY -> evalMetaProperty((MetaProperty) expression, env);
            case SUPER_EXPRESSION -> throw new SyntaxErrorException("'super' keyword unexpected here");
            default -> throw new UnsupportedNodeException(expression.getType().name());
        };
    }

    JsValue evalMetaProperty(MetaProperty meta, Environment env) {
        return "new".equals(meta.getMeta()) ? env.resolveNewTarget() : interpreter.modules.evalMetaProperty();
    }
}
