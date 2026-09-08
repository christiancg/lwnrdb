package org.techhouse.simplejs.internal.parser;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsBigInt;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.TemplateLiteral;

abstract class LiteralProductions extends ClassProductions {
    protected LiteralProductions(String source, List<JsBaseElement> tokens, List<SourcePosition> positions,
            List<Boolean> newlineBefore, boolean strictScriptGoal) {
        super(source, tokens, positions, newlineBefore, strictScriptGoal);
    }

    protected Expression parseSeparatorPrimary() {
        final char c = ((JsSeparator) current()).getValue();
        return switch (c) {
            case '(' -> parseParenOrArrow();
            case '[' -> parseArray();
            case '{' -> parseObject();
            default -> throw error();
        };
    }

    protected Expression parseParenOrArrow() {
        if (matchingParenFollowedByArrow()) {
            final var start = spanStart();
            final var params = parseParams();
            if (newlineBeforeCurrent()) {
                throw error();
            }
            expectOperator("=>");
            return parseArrowBody(params, false, start);
        }
        expectSeparator('(');
        final var expr = withInAllowed(this::parseExpression);
        expectSeparator(')');
        parenthesised.add(expr);
        if (isOperator("=") && (expr instanceof ObjectExpression || expr instanceof ArrayExpression)) {
            throw new SyntaxErrorException("Invalid destructuring assignment target");
        }
        return expr;
    }

    protected boolean matchingParenFollowedByArrow() {
        return matchingParenFollowedByArrowFrom(pos);
    }

    protected boolean matchingParenFollowedByArrowFrom(int from) {
        var depth = 0;
        final var size = tokens.size();
        for (var i = from; i < size; i++) {
            final var t = tokens.get(i);
            if (t.getType() == JsType.SEPARATOR) {
                final char c = ((JsSeparator) t).getValue();
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        final var next = tokens.get(Math.min(i + 1, size - 1));
                        return next.getType() == JsType.OPERATOR && "=>".equals(((JsOperator) next).getValue());
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected ArrowFunctionExpression parseArrowBody(List<JsNode> params, boolean async, int start) {
        if (params.stream().anyMatch(GrammarPredicates::containsYieldOrAwait)) {
            throw new SyntaxErrorException("Arrow parameters may not contain 'yield' or 'await' expressions");
        }
        final var wasInFunctionBody = inFunctionBody;
        final var wasAwaitReserved = awaitReserved;
        inFunctionBody = true;
        awaitReserved = async;
        try {
            return inFunctionKind(async, () -> inBreakableBoundary(() -> {
                final ArrowFunctionExpression arrow;
                if (isSeparator('{')) {
                    final var body = inFunctionScope(params, this::parseBlockBody);
                    checkUseStrictWithSimpleParams(params, body);
                    arrow = new ArrowFunctionExpression(params, body, false, async);
                } else {
                    arrow = new ArrowFunctionExpression(params, parseAssignment(), true, async);
                }
                arrow.setSourceText(spanFrom(start));
                return arrow;
            }));
        } finally {
            inFunctionBody = wasInFunctionBody;
            awaitReserved = wasAwaitReserved;
        }
    }

    protected ArrayExpression parseArray() {
        expectSeparator('[');
        final var elements = new ArrayList<Expression>();
        var trailingComma = false;
        while (!isSeparator(']')) {
            if (matchSeparator(',')) {
                elements.add(null);
                trailingComma = false;
                continue;
            }
            elements.add(parseSpreadableExpression());
            trailingComma = false;
            if (!isSeparator(']')) {
                expectSeparator(',');
                trailingComma = true;
            }
        }
        expectSeparator(']');
        return new ArrayExpression(elements, trailingComma);
    }

    protected ObjectExpression parseObject() {
        expectSeparator('{');
        final var properties = new ArrayList<JsNode>();
        var trailingComma = false;
        if (!isSeparator('}')) {
            do {
                if (isSeparator('}')) {
                    break;
                }
                properties.add(parseObjectMember());
                trailingComma = matchSeparator(',');
            } while (trailingComma);
        }
        expectSeparator('}');
        final var object = new ObjectExpression(properties, trailingComma);
        if (hasDuplicateProto(properties)) {
            duplicateProto.add(object);
        }
        return object;
    }

    protected JsNode parseObjectMember() {
        if (matchOperator("...")) {
            return new SpreadElement(withInAllowed(this::parseAssignment));
        }
        return withInAllowed(this::parseProperty);
    }

    protected Property parseProperty() {
        final var start = spanStart();
        var async = false;
        if (isKeyword("async") && starOrKeyFollows() && !newlineBeforePeek()) {
            advance();
            async = true;
        }
        final var generator = matchOperator("*");
        var kind = "init";
        if (!async && !generator && (isIdentifier("get") || isIdentifier("set")) && beginsPropertyKey(peek())) {
            kind = ((JsIdentifier) current()).getValue();
            advance();
        }
        final var computed = isSeparator('[');
        final var identifierToken = current().getType() == JsType.IDENTIFIER;
        final var fromIdentifier = identifierToken || current().getType() == JsType.KEYWORD && keywordIsIdentifier();
        final var escapedReserved = identifierToken && ((JsIdentifier) current()).isEscaped()
                && isReservedWord(((JsIdentifier) current()).getValue());
        final var key = parsePropertyKey();
        if (isSeparator('(')) {
            final var parts = parseFunctionParts(generator, async, false, true);
            checkAccessorParams(kind, parts.params());
            final var value = new FunctionExpression(null, parts.params(), parts.body(), async, generator);
            value.setSourceText(spanFrom(start));
            return new Property(key, value, computed, false, "init".equals(kind) ? "method" : kind);
        }
        if (async || generator || !"init".equals(kind)) {
            throw error();
        }
        if (matchOperator(":")) {
            return new Property(key, parseAssignment(), computed, false);
        }
        if (!computed && fromIdentifier) {
            if (escapedReserved) {
                throw new SyntaxErrorException(
                        "Keyword must not contain escaped characters: " + ((Identifier) key).getName());
            }
            if (matchOperator("=")) {
                return new Property(key, new AssignmentExpression("=", key, parseAssignment()), false, true);
            }
            return new Property(key, key, false, true);
        }
        throw error();
    }

    protected Expression parsePropertyKey() {
        if (isSeparator('[')) {
            advance();
            final var key = parseAssignment();
            expectSeparator(']');
            return key;
        }
        final var t = current();
        final Expression key = switch (t.getType()) {
            case STRING -> new StringLiteral(((JsString) t).getValue());
            case NUMBER -> new NumberLiteral(((JsNumber) t).getValue());
            case BIGINT -> new StringLiteral(((JsBigInt) t).getValue().toString());
            default -> literalPropertyKey(t);
        };
        advance();
        return key;
    }

    protected boolean beginsPropertyKey(JsBaseElement t) {
        return switch (t.getType()) {
            case IDENTIFIER, KEYWORD, STRING, NUMBER, BOOLEAN, NULL, UNDEFINED -> true;
            case SEPARATOR -> ((JsSeparator) t).getValue() == '[';
            default -> false;
        };
    }

    protected boolean starOrKeyFollows() {
        final var next = peek();
        return (next.getType() == JsType.OPERATOR && "*".equals(((JsOperator) next).getValue()))
                || beginsPropertyKey(next);
    }

    protected boolean isIdentifier(String name) {
        final var t = current();
        return t.getType() == JsType.IDENTIFIER && ((JsIdentifier) t).getValue().equals(name)
                && !((JsIdentifier) t).isEscaped();
    }

    @Override
    protected TemplateLiteral parseTemplate(JsTemplateString template, boolean tagged) {
        if (!tagged) {
            for (final var quasi : template.getQuasis()) {
                if (quasi == null) {
                    throw new SyntaxErrorException("Invalid escape sequence in template literal");
                }
            }
        }
        final var expressions = new ArrayList<Expression>();
        for (final var expressionTokens : template.getExpressions()) {
            expressions.add(forTemplateExpression(expressionTokens).parseTemplateExpression());
        }
        return new TemplateLiteral(template.getQuasis(), template.getRawQuasis(), expressions);
    }

    protected ParserProductions forTemplateExpression(List<JsBaseElement> tokens) {
        final var nested = new ParserProductions(null, tokens, null, null, strictScriptGoal);
        nested.inGenerator = inGenerator;
        nested.inAsync = inAsync;
        nested.superCallAllowed = superCallAllowed;
        nested.inStaticBlock = inStaticBlock;
        nested.classHasHeritage = classHasHeritage;
        nested.privateScope = privateScope;
        nested.inFunctionBody = inFunctionBody;
        nested.inNonArrowFunction = inNonArrowFunction;
        nested.awaitReserved = awaitReserved;
        return nested;
    }

    protected Expression parseTemplateExpression() {
        final var expr = parseExpression();
        if (!atEnd()) {
            throw error();
        }
        return expr;
    }
}
