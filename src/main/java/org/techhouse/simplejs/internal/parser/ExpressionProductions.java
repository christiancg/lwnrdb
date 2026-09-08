package org.techhouse.simplejs.internal.parser;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsPrivateIdentifier;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.AwaitExpression;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.ConditionalExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.MetaProperty;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.SequenceExpression;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.nodes.TaggedTemplateExpression;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UndefinedLiteral;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.YieldExpression;

abstract class ExpressionProductions extends BindingProductions {
    protected ExpressionProductions(String source, List<JsBaseElement> tokens, List<SourcePosition> positions,
            List<Boolean> newlineBefore, boolean strictScriptGoal) {
        super(source, tokens, positions, newlineBefore, strictScriptGoal);
    }

    @Override
    protected Expression parseExpression() {
        final var first = parseAssignment();
        if (!isSeparator(',')) {
            return first;
        }
        final var expressions = new ArrayList<Expression>();
        expressions.add(first);
        while (matchSeparator(',')) {
            expressions.add(parseAssignment());
        }
        return new SequenceExpression(expressions);
    }

    @Override
    protected Expression parseAssignment() {
        if (isKeyword("yield")) {
            return parseYield();
        }
        final var left = parseConditional();
        if (current().getType() == JsType.OPERATOR) {
            final var op = ((JsOperator) current()).getValue();
            if (ParserTables.ASSIGNMENT_OPERATORS.contains(op)) {
                final var resolvedLeft = left instanceof UndefinedLiteral ? new Identifier("undefined") : left;
                final var targetParenthesized = parenthesised.contains(left);
                final var target = patterns.resolveAssignmentTarget(resolvedLeft, op);
                advance();
                return new AssignmentExpression(op, target, parseAssignment(), targetParenthesized);
            }
        }
        if (duplicateProto.contains(left)) {
            throw new SyntaxErrorException("Duplicate __proto__ fields are not allowed in object literals");
        }
        return left;
    }

    protected Expression parseYield() {
        if (!inGenerator) {
            throw new SyntaxErrorException("yield is only valid inside a generator");
        }
        expectKeyword("yield");
        if (newlineBeforeCurrent()) {
            return new YieldExpression(null, false);
        }
        final var delegate = matchOperator("*");
        Expression argument = null;
        if (delegate) {
            argument = parseAssignment();
        } else if (hasYieldArgument()) {
            argument = parseAssignment();
        }
        return new YieldExpression(argument, delegate);
    }

    protected boolean hasYieldArgument() {
        final var t = current();
        if (t.getType() == JsType.EOF) {
            return false;
        }
        if (t.getType() == JsType.SEPARATOR) {
            final char c = ((JsSeparator) t).getValue();
            return c != ')' && c != ']' && c != '}' && c != ',' && c != ';';
        }
        return !(t.getType() == JsType.OPERATOR && ":".equals(((JsOperator) t).getValue()));
    }

    protected Expression parseConditional() {
        final var test = parseBinary(0);
        if (matchOperator("?")) {
            final var consequent = withInAllowed(this::parseAssignment);
            expectOperator(":");
            final var alternate = parseAssignment();
            return new ConditionalExpression(test, consequent, alternate);
        }
        return test;
    }

    protected Expression parseBinary(int minPrec) {
        var left = parseUnary();
        var op = currentBinaryOperator();
        while (op != null && ParserTables.BINARY_PRECEDENCE.get(op) >= minPrec) {
            final int prec = ParserTables.BINARY_PRECEDENCE.get(op);
            advance();
            if ("in".equals(op) && left instanceof PrivateIdentifier) {
                rejectPrivateInRhs();
            }
            final int nextMinPrec = "**".equals(op) ? prec : prec + 1;
            final var right = parseBinary(nextMinPrec);
            if (ParserTables.LOGICAL_OPERATORS.contains(op)) {
                rejectCoalesceChain(op, left);
                rejectCoalesceChain(op, right);
                left = new LogicalExpression(op, left, right);
            } else {
                left = new BinaryExpression(op, left, right);
            }
            op = currentBinaryOperator();
        }
        return left;
    }

    protected void rejectCoalesceChain(String op, Expression operand) {
        if (!(operand instanceof LogicalExpression logical) || parenthesised.contains(operand)) {
            return;
        }
        final var inner = logical.getOperator();
        if ("??".equals(op) != "??".equals(inner)) {
            throw new SyntaxErrorException("Cannot chain '" + inner + "' with '" + op + "' without parentheses");
        }
    }

    protected void rejectPrivateInRhs() {
        if (current().getType() == JsType.PRIVATE_IDENTIFIER) {
            throw new SyntaxErrorException("Unexpected private field in right-hand side of 'in'");
        }
        if (startsArrowFunction()) {
            throw new SyntaxErrorException("Unexpected arrow function in right-hand side of 'in'");
        }
    }

    protected String currentBinaryOperator() {
        final var t = current();
        if (t.getType() == JsType.OPERATOR) {
            final var op = ((JsOperator) t).getValue();
            return ParserTables.BINARY_PRECEDENCE.containsKey(op) ? op : null;
        }
        if (t.getType() == JsType.KEYWORD) {
            final var kw = ((JsKeyword) t).getValue();
            if ("in".equals(kw)) {
                return Boolean.TRUE.equals(noInStack.peek()) ? null : "in";
            }
            return "instanceof".equals(kw) ? kw : null;
        }
        return null;
    }

    protected Expression parseUnary() {
        final var t = current();
        if (t.getType() == JsType.OPERATOR) {
            final var op = ((JsOperator) t).getValue();
            if (ParserTables.PREFIX_UNARY_OPERATORS.contains(op)) {
                advance();
                return rejectExponentiationBase(new UnaryExpression(op, parseUnary(), true));
            }
            if ("++".equals(op) || "--".equals(op)) {
                advance();
                final var argument = parseUnary();
                checkUpdateTarget(argument);
                return new UpdateExpression(op, argument, true);
            }
        }
        if (t.getType() == JsType.KEYWORD) {
            final var kw = ((JsKeyword) t).getValue();
            if ("delete".equals(kw)) {
                advance();
                final var argument = parseUnary();
                if (argument instanceof Identifier) {
                    throw new SyntaxErrorException("Delete of an unqualified identifier in strict mode");
                }
                if (argument instanceof MemberExpression member && member.getProperty() instanceof PrivateIdentifier) {
                    throw new SyntaxErrorException("Private fields can not be deleted");
                }
                return rejectExponentiationBase(new UnaryExpression(kw, argument, true));
            }
            if ("typeof".equals(kw) || "void".equals(kw)) {
                advance();
                return rejectExponentiationBase(new UnaryExpression(kw, parseUnary(), true));
            }
            if ("await".equals(kw) && !awaitIsIdentifierReference()) {
                advance();
                if (!inAsync) {
                    throw new SyntaxErrorException("await is only valid inside an async function");
                }
                return rejectExponentiationBase(new AwaitExpression(parseUnary()));
            }
        }
        return parsePostfix();
    }

    protected Expression rejectExponentiationBase(Expression unary) {
        if (isOperator("**")) {
            throw new SyntaxErrorException("Unary operator used immediately before an exponentiation expression");
        }
        return unary;
    }

    protected Expression parsePostfix() {
        final var expr = parseCallMember();
        if ((isOperator("++") || isOperator("--")) && !newlineBeforeCurrent()) {
            final var op = ((JsOperator) advance()).getValue();
            checkUpdateTarget(expr);
            return new UpdateExpression(op, expr, false);
        }
        return expr;
    }

    protected Expression parseCallMember() {
        final var base = isKeyword("new") ? parseNew() : parsePrimary();
        return parseCallMemberTail(base);
    }

    protected Expression parseCallMemberTail(Expression start) {
        var expr = start;
        var advancing = true;
        var optionalChain = false;
        while (advancing) {
            if (matchOperator(".")) {
                expr = memberExpression(expr, parseMemberProperty(), false);
            } else if (matchOperator("?.")) {
                optionalChain = true;
                expr = parseOptionalTail(expr);
            } else if (isSeparator('[')) {
                advance();
                final var property = parseExpression();
                expectSeparator(']');
                expr = new MemberExpression(expr, property, true, false);
            } else if (isSeparator('(')) {
                final var callPosition = positionAt(pos);
                expr = new CallExpression(expr, parseArguments());
                expr.setPosition(callPosition);
            } else if (current().getType() == JsType.TEMPLATE_STRING) {
                if (optionalChain) {
                    throw new SyntaxErrorException("Invalid tagged template on an optional chain");
                }
                final var template = parseTemplate((JsTemplateString) advance(), true);
                expr = new TaggedTemplateExpression(expr, template);
            } else {
                advancing = false;
            }
        }
        return expr;
    }

    protected Expression parseOptionalTail(Expression object) {
        if (isSeparator('(')) {
            final var callPosition = positionAt(pos);
            final var call = new CallExpression(object, parseArguments(), true);
            call.setPosition(callPosition);
            return call;
        }
        if (isSeparator('[')) {
            advance();
            final var property = withInAllowed(this::parseExpression);
            expectSeparator(']');
            return new MemberExpression(object, property, true, true);
        }
        return memberExpression(object, parseMemberProperty(), true);
    }

    protected Expression parseMemberProperty() {
        final var t = current();
        final var name = identifierName(t);
        if (name != null) {
            advance();
            return new Identifier(name);
        }
        if (t.getType() == JsType.PRIVATE_IDENTIFIER) {
            advance();
            return referencePrivateName(((JsPrivateIdentifier) t).getValue());
        }
        throw error();
    }

    @Override
    protected Identifier literalPropertyKey(JsBaseElement t) {
        final var name = identifierName(t);
        if (name == null) {
            throw error();
        }
        return new Identifier(name);
    }

    protected PrivateIdentifier referencePrivateName(String name) {
        if (privateScope == null) {
            throw PrivateScope.undeclared(name);
        }
        privateScope.reference(name);
        return new PrivateIdentifier(name);
    }

    protected MemberExpression memberExpression(Expression object, Expression property, boolean optional) {
        if (object instanceof SuperExpression && property instanceof PrivateIdentifier) {
            throw new SyntaxErrorException("Private fields can not be accessed on 'super'");
        }
        return new MemberExpression(object, property, false, optional);
    }

    protected Expression parseNew() {
        final var newPosition = positionAt(pos);
        expectKeyword("new");
        if (matchOperator(".")) {
            if (!isContextualKeyword("target")) {
                throw error();
            }
            rejectOutsideFunctionCode("new.target");
            advance();
            return new MetaProperty("new", "target");
        }
        var callee = isKeyword("new") ? parseNew() : parsePrimary();
        callee = parseNewCalleeTail(callee);
        List<Expression> arguments = List.of();
        if (isSeparator('(')) {
            arguments = parseArguments();
        }
        final var expression = new NewExpression(callee, arguments);
        expression.setPosition(newPosition);
        return expression;
    }

    protected Expression parseNewCalleeTail(Expression start) {
        var expr = start;
        var advancing = true;
        while (advancing) {
            if (matchOperator(".")) {
                expr = memberExpression(expr, parseMemberProperty(), false);
            } else if (isSeparator('[')) {
                advance();
                final var property = parseExpression();
                expectSeparator(']');
                expr = new MemberExpression(expr, property, true, false);
            } else if (current().getType() == JsType.TEMPLATE_STRING) {
                final var template = parseTemplate((JsTemplateString) advance(), true);
                expr = new TaggedTemplateExpression(expr, template);
            } else {
                advancing = false;
            }
        }
        return expr;
    }

    protected List<Expression> parseArguments() {
        expectSeparator('(');
        final var arguments = new ArrayList<Expression>();
        if (!isSeparator(')')) {
            do {
                if (isSeparator(')')) {
                    break;
                }
                arguments.add(parseSpreadableExpression());
            } while (matchSeparator(','));
        }
        expectSeparator(')');
        return arguments;
    }

    protected Expression parseSpreadableExpression() {
        if (matchOperator("...")) {
            return new SpreadElement(withInAllowed(this::parseAssignment));
        }
        return withInAllowed(this::parseAssignment);
    }
}
