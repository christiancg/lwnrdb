package org.techhouse.simplejs.internal.parser;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsBigInt;
import org.techhouse.simplejs.elements.JsBoolean;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsPrivateIdentifier;
import org.techhouse.simplejs.elements.JsRegex;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.nodes.BigIntLiteral;
import org.techhouse.simplejs.nodes.BooleanLiteral;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.ImportExpression;
import org.techhouse.simplejs.nodes.MetaProperty;
import org.techhouse.simplejs.nodes.NullLiteral;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.RegexLiteral;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.nodes.ThisExpression;
import org.techhouse.simplejs.nodes.UndefinedLiteral;

public final class ParserProductions extends LiteralProductions {
    public ParserProductions(String source, List<JsBaseElement> tokens, List<SourcePosition> positions,
            List<Boolean> newlineBefore, boolean strictScriptGoal) {
        super(source, tokens, positions, newlineBefore, strictScriptGoal);
    }

    @Override
    protected FunctionParts parseFunctionParts(boolean generator, boolean async) {
        return parseFunctionParts(generator, async, false, false);
    }

    @Override
    protected FunctionParts parseFunctionParts(boolean generator, boolean async, boolean superCall, boolean method) {
        final var wasSuperPropertyAllowed = superPropertyAllowed;
        superPropertyAllowed = method;
        try {
            return parseFunctionPartsInContext(generator, async, superCall);
        } finally {
            superPropertyAllowed = wasSuperPropertyAllowed;
        }
    }

    private FunctionParts parseFunctionPartsInContext(boolean generator, boolean async, boolean superCall) {
        final var wasInStaticBlock = inStaticBlock;
        final var wasInGenerator = inGenerator;
        final var wasInAsync = inAsync;
        final var wasSuperCallAllowed = superCallAllowed;
        final var wasInFunctionBody = inFunctionBody;
        final var wasInNonArrowFunction = inNonArrowFunction;
        final var wasAwaitReserved = awaitReserved;
        inStaticBlock = false;
        superCallAllowed = superCall;
        inFunctionBody = true;
        inNonArrowFunction = true;
        awaitReserved = async;
        try {
            final var params = outsideGenerator(this::parseParams);
            setFunctionKind(generator, async);
            final var body = inBreakableBoundary(() -> inFunctionScope(params, this::parseBlockBody));
            checkUseStrictWithSimpleParams(params, body);
            return new FunctionParts(params, body);
        } finally {
            inStaticBlock = wasInStaticBlock;
            inGenerator = wasInGenerator;
            inAsync = wasInAsync;
            superCallAllowed = wasSuperCallAllowed;
            inFunctionBody = wasInFunctionBody;
            inNonArrowFunction = wasInNonArrowFunction;
            awaitReserved = wasAwaitReserved;
        }
    }

    public Program parseProgram() {
        final var body = new ArrayList<Statement>();
        while (!atEnd()) {
            body.add(parseStatement());
        }
        return new Program(body);
    }

    @Override
    protected Expression parsePrimary() {
        final var t = current();
        switch (t.getType()) {
            case NUMBER -> {
                advance();
                return new NumberLiteral(((JsNumber) t).getValue());
            }
            case BIGINT -> {
                advance();
                return new BigIntLiteral(((JsBigInt) t).getValue());
            }
            case STRING -> {
                advance();
                return new StringLiteral(((JsString) t).getValue());
            }
            case BOOLEAN -> {
                advance();
                return new BooleanLiteral(((JsBoolean) t).getValue());
            }
            case NULL -> {
                advance();
                return new NullLiteral();
            }
            case UNDEFINED -> {
                advance();
                return new UndefinedLiteral();
            }
            case REGEX -> {
                advance();
                final var pattern = ((JsRegex) t).getPattern();
                final var flags = ((JsRegex) t).getFlags();
                RegexTranslator.compile(pattern, flags);
                return new RegexLiteral(pattern, flags);
            }
            case TEMPLATE_STRING -> {
                advance();
                return parseTemplate((JsTemplateString) t, false);
            }
            case IDENTIFIER -> {
                if (inStaticBlock && "arguments".equals(((JsIdentifier) t).getValue())) {
                    throw new SyntaxErrorException("'arguments' is not allowed in a class static block");
                }
                return parseIdentifierOrArrow();
            }
            case PRIVATE_IDENTIFIER -> {
                advance();
                if (!isKeyword("in")) {
                    throw error();
                }
                return referencePrivateName(((JsPrivateIdentifier) t).getValue());
            }
            case KEYWORD -> {
                return parseKeywordPrimary();
            }
            case SEPARATOR -> {
                return parseSeparatorPrimary();
            }
            default -> throw error();
        }
    }

    private Expression parseIdentifierOrArrow() {
        final var t = (JsIdentifier) current();
        if (t.isEscaped() && isEscapeReserved(t.getValue())) {
            throw new SyntaxErrorException("Keyword must not contain escaped characters: " + t.getValue());
        }
        if (isEscapeReserved(t.getValue()) || ParserTables.STRICT_RESERVED.contains(t.getValue())) {
            throw new SyntaxErrorException("'" + t.getValue() + "' cannot be used as an identifier in strict mode");
        }
        if (peek().getType() == JsType.OPERATOR && "=>".equals(((JsOperator) peek()).getValue())) {
            if (newlineBeforePeek()) {
                throw error();
            }
            final var start = spanStart();
            advance();
            advance();
            validateBindingName(t.getValue());
            return parseArrowBody(List.of(new Identifier(t.getValue())), false, start);
        }
        advance();
        return new Identifier(t.getValue());
    }

    private Expression parseKeywordPrimary() {
        final var kw = ((JsKeyword) current()).getValue();
        return switch (kw) {
            case "this" -> {
                advance();
                yield new ThisExpression();
            }
            case "function" -> parseFunctionExpression(false, spanStart());
            case "async" -> asyncStartsFunctionOrArrow() ? parseAsyncPrimary() : parseContextualIdentifier("async");
            case "of" -> parseContextualIdentifier("of");
            case "await" -> {
                if (awaitIsReserved()) {
                    throw error();
                }
                yield parseContextualIdentifier("await");
            }
            case "class" -> parseClassExpression();
            case "super" -> {
                rejectOutsideFunctionCode("super");
                advance();
                if (isSeparator('(')) {
                    if (!superCallAllowed) {
                        throw new SyntaxErrorException(
                                "'super' keyword unexpected here: a super call belongs to a derived constructor");
                    }
                } else if (!superPropertyAllowed) {
                    throw new SyntaxErrorException(
                            "'super' keyword unexpected here: a super property belongs to a method");
                }
                yield new SuperExpression();
            }
            case "import" -> parseImportExpressionOrMeta();
            default -> throw error();
        };
    }

    private Expression parseImportExpressionOrMeta() {
        expectKeyword("import");
        if (matchOperator(".")) {
            if (!isContextualKeyword("meta")) {
                throw error();
            }
            rejectInScriptGoal("import.meta");
            advance();
            return new MetaProperty("import", "meta");
        }
        expectSeparator('(');
        final var source = withInAllowed(this::parseAssignment);
        Expression options = null;
        if (matchSeparator(',') && !isSeparator(')')) {
            options = withInAllowed(this::parseAssignment);
            matchSeparator(',');
        }
        expectSeparator(')');
        return new ImportExpression(source, options);
    }

    @Override
    protected boolean isDynamicImportOrMeta() {
        final var next = peek();
        return (next.getType() == JsType.SEPARATOR && ((JsSeparator) next).getValue() == '(')
                || (next.getType() == JsType.OPERATOR && ".".equals(((JsOperator) next).getValue()));
    }

    private boolean asyncStartsFunctionOrArrow() {
        final var next = peek();
        if (next.getType() == JsType.KEYWORD && "function".equals(((JsKeyword) next).getValue())) {
            return !newlineBeforePeek();
        }
        if (newlineBeforePeek()) {
            return false;
        }
        if (next.getType() == JsType.SEPARATOR && ((JsSeparator) next).getValue() == '(') {
            return matchingParenFollowedByArrowFrom(pos + 1);
        }
        if (next.getType() != JsType.IDENTIFIER
                && !(next.getType() == JsType.KEYWORD && CONTEXTUAL_KEYWORDS.contains(((JsKeyword) next).getValue()))) {
            return false;
        }
        final var after = peekAt(2);
        return after.getType() == JsType.OPERATOR && "=>".equals(((JsOperator) after).getValue());
    }

    private Expression parseAsyncPrimary() {
        final var start = spanStart();
        expectKeyword("async");
        if (isKeyword("function")) {
            return parseFunctionExpression(true, start);
        }
        if (peek().getType() == JsType.OPERATOR && "=>".equals(((JsOperator) peek()).getValue())) {
            if (newlineBeforePeek()) {
                throw error();
            }
            final var param = withAwaitReserved(true, this::parseBindingIdentifier);
            expectOperator("=>");
            return parseArrowBody(List.of(param), true, start);
        }
        if (isSeparator('(') && matchingParenFollowedByArrow()) {
            final var params = withAwaitReserved(true, this::parseParams);
            if (newlineBeforeCurrent()) {
                throw error();
            }
            expectOperator("=>");
            return parseArrowBody(params, true, start);
        }
        throw error();
    }

    private FunctionExpression parseFunctionExpression(boolean async, int start) {
        expectKeyword("function");
        final var generator = matchOperator("*");
        final var name = withAwaitReserved(async, () -> identifierLikeAt(0) ? parseBindingIdentifier() : null);
        final var parts = parseFunctionParts(generator, async);
        final var expression = new FunctionExpression(name, parts.params(), parts.body(), async, generator);
        expression.setSourceText(spanFrom(start));
        return expression;
    }

    @Override
    protected boolean startsArrowFunction() {
        if (isSeparator('(')) {
            return matchingParenFollowedByArrow();
        }
        final var asyncPrefix = isKeyword("async") && !newlineBeforePeek();
        final var start = asyncPrefix ? 1 : 0;
        final var head = peekAt(start);
        if (head.getType() == JsType.IDENTIFIER) {
            final var next = peekAt(start + 1);
            return next.getType() == JsType.OPERATOR && "=>".equals(((JsOperator) next).getValue());
        }
        return asyncPrefix && head.getType() == JsType.SEPARATOR && ((JsSeparator) head).getValue() == '('
                && matchingParenFollowedByArrowFrom(pos + 1);
    }
}
