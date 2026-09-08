package org.techhouse.simplejs.internal.parser;

import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBoolean;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.lexer.LexerTables;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.AwaitExpression;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.ConditionalExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RestElement;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.SequenceExpression;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.nodes.TaggedTemplateExpression;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.VariableDeclarator;
import org.techhouse.simplejs.nodes.YieldExpression;

abstract class GrammarPredicates extends TokenStream {
    protected static final Set<String> EXPRESSION_KEYWORDS = Set.of("this", "super", "new", "function", "class",
            "typeof", "void", "delete", "await", "yield", "async", "import", "of");

    protected GrammarPredicates(List<JsBaseElement> tokens, List<SourcePosition> positions,
            List<Boolean> newlineBefore) {
        super(tokens, positions, newlineBefore);
    }

    protected static boolean isSimpleParameterList(List<JsNode> params) {
        for (final var param : params) {
            if (!(param instanceof Identifier)) {
                return false;
            }
        }
        return true;
    }

    protected static boolean containsUseStrictDirective(BlockStatement body) {
        for (final var statement : body.getBody()) {
            if (!(statement instanceof ExpressionStatement stmt)
                    || !(stmt.getExpression() instanceof StringLiteral str)) {
                break;
            }
            if ("use strict".equals(str.getValue())) {
                return true;
            }
        }
        return false;
    }

    protected static void collectNames(JsNode node, List<String> names) {
        switch (node) {
            case null -> {
            }
            case Identifier id -> names.add(id.getName());
            case RestElement rest -> collectNames(rest.getArgument(), names);
            case AssignmentPattern assignment -> collectNames(assignment.getLeft(), names);
            case ArrayPattern array -> array.getElements().forEach(element -> collectNames(element, names));
            case ObjectPattern object -> object.getProperties().forEach(prop -> collectNames(prop, names));
            case Property prop -> collectNames(prop.getValue(), names);
            default -> {
            }
        }
    }

    protected static SyntaxErrorException undefinedLabel(Identifier label) {
        return new SyntaxErrorException("Undefined label '" + label.getName() + "'");
    }

    protected static boolean beginsExpression(JsBaseElement t) {
        return switch (t.getType()) {
            case NUMBER, BIGINT, STRING, BOOLEAN, NULL, UNDEFINED, REGEX, TEMPLATE_STRING, IDENTIFIER,
                    PRIVATE_IDENTIFIER ->
                true;
            case KEYWORD -> EXPRESSION_KEYWORDS.contains(((JsKeyword) t).getValue());
            case SEPARATOR -> {
                final char c = ((JsSeparator) t).getValue();
                yield c == '(' || c == '[' || c == '{';
            }
            case OPERATOR -> {
                final var op = ((JsOperator) t).getValue();
                yield ParserTables.PREFIX_UNARY_OPERATORS.contains(op) || "++".equals(op) || "--".equals(op);
            }
            default -> false;
        };
    }

    protected static boolean isReservedWord(String name) {
        return LexerTables.isReservedWord(name) || ParserTables.STRICT_RESERVED.contains(name)
                || ParserTables.RESTRICTED_BINDINGS.contains(name);
    }

    protected static void checkUpdateTarget(Expression argument) {
        if (argument instanceof Identifier id && ParserTables.RESTRICTED_BINDINGS.contains(id.getName())) {
            throw new SyntaxErrorException("'" + id.getName() + "' cannot be updated in strict mode");
        }
        if (!isSimpleAssignmentTarget(argument)) {
            throw new SyntaxErrorException("Invalid left-hand side expression in an update expression");
        }
    }

    protected static boolean isSimpleAssignmentTarget(Expression expression) {
        if (expression instanceof Identifier) {
            return true;
        }
        return expression instanceof MemberExpression member && !member.isOptional()
                && !isOptionalChain(member.getObject());
    }

    protected static boolean isOptionalChain(Expression expression) {
        return switch (expression) {
            case MemberExpression member -> member.isOptional() || isOptionalChain(member.getObject());
            case CallExpression call -> call.isOptional() || isOptionalChain(call.getCallee());
            case null, default -> false;
        };
    }

    protected static String identifierName(JsBaseElement t) {
        return switch (t.getType()) {
            case IDENTIFIER -> ((JsIdentifier) t).getValue();
            case KEYWORD -> ((JsKeyword) t).getValue();
            case BOOLEAN -> String.valueOf(((JsBoolean) t).getValue());
            case NULL -> "null";
            case UNDEFINED -> "undefined";
            default -> null;
        };
    }

    protected static String propName(MemberKey memberKey) {
        if (memberKey.computed()) {
            return null;
        }
        if (memberKey.key() instanceof Identifier id) {
            return id.getName();
        }
        if (memberKey.key() instanceof StringLiteral str) {
            return str.getValue();
        }
        return null;
    }

    protected static boolean containsArgumentsOrSuperCall(JsNode node) {
        return switch (node) {
            case null -> false;
            case Identifier id -> "arguments".equals(id.getName());
            case CallExpression call ->
                call.getCallee() instanceof SuperExpression || containsArgumentsOrSuperCall(call.getCallee())
                        || call.getArguments().stream().anyMatch(GrammarPredicates::containsArgumentsOrSuperCall);
            case BinaryExpression bin ->
                containsArgumentsOrSuperCall(bin.getLeft()) || containsArgumentsOrSuperCall(bin.getRight());
            case LogicalExpression log ->
                containsArgumentsOrSuperCall(log.getLeft()) || containsArgumentsOrSuperCall(log.getRight());
            case ConditionalExpression cond ->
                containsArgumentsOrSuperCall(cond.getTest()) || containsArgumentsOrSuperCall(cond.getConsequent())
                        || containsArgumentsOrSuperCall(cond.getAlternate());
            case UnaryExpression unary -> containsArgumentsOrSuperCall(unary.getArgument());
            case AssignmentExpression assign -> containsArgumentsOrSuperCall(assign.getValue());
            case SequenceExpression seq ->
                seq.getExpressions().stream().anyMatch(GrammarPredicates::containsArgumentsOrSuperCall);
            case SpreadElement spread -> containsArgumentsOrSuperCall(spread.getArgument());
            case ArrayExpression array ->
                array.getElements().stream().anyMatch(GrammarPredicates::containsArgumentsOrSuperCall);
            case ObjectExpression obj ->
                obj.getProperties().stream().anyMatch(GrammarPredicates::containsArgumentsOrSuperCall);
            case Property prop -> containsArgumentsOrSuperCall(prop.getValue());
            case MemberExpression member ->
                containsArgumentsOrSuperCall(member.getObject()) || containsArgumentsOrSuperCall(member.getProperty());
            case ArrowFunctionExpression arrow -> containsArgumentsOrSuperCall(arrow.getBody());
            case BlockStatement block ->
                block.getBody().stream().anyMatch(GrammarPredicates::containsArgumentsOrSuperCall);
            case ExpressionStatement stmt -> containsArgumentsOrSuperCall(stmt.getExpression());
            case ReturnStatement ret -> containsArgumentsOrSuperCall(ret.getArgument());
            case VariableDeclaration decl ->
                decl.getDeclarations().stream().anyMatch(GrammarPredicates::containsArgumentsOrSuperCall);
            case VariableDeclarator declarator -> containsArgumentsOrSuperCall(declarator.getInit());
            case IfStatement ifStmt ->
                containsArgumentsOrSuperCall(ifStmt.getTest()) || containsArgumentsOrSuperCall(ifStmt.getConsequent())
                        || containsArgumentsOrSuperCall(ifStmt.getAlternate());
            default -> false;
        };
    }

    protected static boolean containsYieldOrAwait(JsNode node) {
        return switch (node) {
            case null -> false;
            case YieldExpression ignored -> true;
            case AwaitExpression ignored -> true;
            case AssignmentPattern pattern ->
                containsYieldOrAwait(pattern.getLeft()) || containsYieldOrAwait(pattern.getRight());
            case ArrayPattern pattern ->
                pattern.getElements().stream().anyMatch(GrammarPredicates::containsYieldOrAwait);
            case ObjectPattern pattern ->
                pattern.getProperties().stream().anyMatch(GrammarPredicates::containsYieldOrAwait);
            case RestElement rest -> containsYieldOrAwait(rest.getArgument());
            case Property prop ->
                (prop.isComputed() && containsYieldOrAwait(prop.getKey())) || containsYieldOrAwait(prop.getValue());
            case AssignmentExpression assign ->
                containsYieldOrAwait(assign.getTarget()) || containsYieldOrAwait(assign.getValue());
            case BinaryExpression bin -> containsYieldOrAwait(bin.getLeft()) || containsYieldOrAwait(bin.getRight());
            case LogicalExpression log -> containsYieldOrAwait(log.getLeft()) || containsYieldOrAwait(log.getRight());
            case ConditionalExpression cond -> containsYieldOrAwait(cond.getTest())
                    || containsYieldOrAwait(cond.getConsequent()) || containsYieldOrAwait(cond.getAlternate());
            case UnaryExpression unary -> containsYieldOrAwait(unary.getArgument());
            case UpdateExpression update -> containsYieldOrAwait(update.getArgument());
            case SequenceExpression seq ->
                seq.getExpressions().stream().anyMatch(GrammarPredicates::containsYieldOrAwait);
            case SpreadElement spread -> containsYieldOrAwait(spread.getArgument());
            case ArrayExpression array ->
                array.getElements().stream().anyMatch(GrammarPredicates::containsYieldOrAwait);
            case ObjectExpression obj -> obj.getProperties().stream().anyMatch(GrammarPredicates::containsYieldOrAwait);
            case CallExpression call -> containsYieldOrAwait(call.getCallee())
                    || call.getArguments().stream().anyMatch(GrammarPredicates::containsYieldOrAwait);
            case NewExpression newExpr -> containsYieldOrAwait(newExpr.getCallee())
                    || newExpr.getArguments().stream().anyMatch(GrammarPredicates::containsYieldOrAwait);
            case MemberExpression member -> containsYieldOrAwait(member.getObject())
                    || (member.isComputed() && containsYieldOrAwait(member.getProperty()));
            case TemplateLiteral template ->
                template.getExpressions().stream().anyMatch(GrammarPredicates::containsYieldOrAwait);
            case TaggedTemplateExpression tagged ->
                containsYieldOrAwait(tagged.getTag()) || containsYieldOrAwait(tagged.getQuasi());
            default -> false;
        };
    }

    protected static boolean hasDuplicateProto(List<JsNode> properties) {
        var seen = false;
        for (final var member : properties) {
            if (!(member instanceof Property property) || property.isComputed() || property.isShorthand()
                    || !"init".equals(property.getKind()) || !"__proto__".equals(propertyKeyName(property))) {
                continue;
            }
            if (seen) {
                return true;
            }
            seen = true;
        }
        return false;
    }

    protected static String propertyKeyName(Property property) {
        return switch (property.getKey()) {
            case Identifier id -> id.getName();
            case StringLiteral str -> str.getValue();
            case null, default -> null;
        };
    }
}
