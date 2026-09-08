package org.techhouse.simplejs.internal.parser;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.BreakStatement;
import org.techhouse.simplejs.nodes.CatchClause;
import org.techhouse.simplejs.nodes.ContinueStatement;
import org.techhouse.simplejs.nodes.EmptyStatement;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.LabeledStatement;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.SwitchCase;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.ThrowStatement;
import org.techhouse.simplejs.nodes.TryStatement;

abstract class StatementProductions extends LoopProductions {
    protected StatementProductions(String source, List<JsBaseElement> tokens, List<SourcePosition> positions,
            List<Boolean> newlineBefore, boolean strictScriptGoal) {
        super(source, tokens, positions, newlineBefore, strictScriptGoal);
    }

    @Override
    protected Statement parseStatement() {
        final var position = positionAt(pos);
        final var statement = parseStatementBody();
        if (statement.getPosition() == null) {
            statement.setPosition(position);
        }
        return statement;
    }

    protected Statement parseStatementBody() {
        if (isSeparator('{')) {
            return parseBlock();
        }
        if (matchSeparator(';')) {
            return new EmptyStatement();
        }
        if (current().getType() == JsType.KEYWORD) {
            switch (((JsKeyword) current()).getValue()) {
                case "var", "let", "const" -> {
                    return parseVariableDeclaration();
                }
                case "if" -> {
                    return parseIf();
                }
                case "while" -> {
                    return parseWhile();
                }
                case "do" -> {
                    return parseDoWhile();
                }
                case "for" -> {
                    return parseFor();
                }
                case "try" -> {
                    return parseTry();
                }
                case "throw" -> {
                    return parseThrow();
                }
                case "switch" -> {
                    return parseSwitch();
                }
                case "return" -> {
                    return parseReturn();
                }
                case "break" -> {
                    return parseBreak();
                }
                case "continue" -> {
                    return parseContinue();
                }
                case "function" -> {
                    return parseFunctionDeclaration(false, spanStart());
                }
                case "async" -> {
                    if (!newlineBeforePeek() && peek().getType() == JsType.KEYWORD
                            && "function".equals(((JsKeyword) peek()).getValue())) {
                        final var start = spanStart();
                        advance();
                        return parseFunctionDeclaration(true, start);
                    }
                }
                case "class" -> {
                    return parseClassDeclaration();
                }
                case "import" -> {
                    if (isDynamicImportOrMeta()) {
                        return parseExpressionStatement();
                    }
                    return parseImportDeclaration();
                }
                case "export" -> {
                    return parseExportDeclaration();
                }
                default -> {
                }
            }
        }
        if (isContextualKeyword("with") && peek().getType() == JsType.SEPARATOR
                && ((JsSeparator) peek()).getValue() == '(') {
            throw new SyntaxErrorException("Strict mode code may not include a with statement");
        }
        if (isContextualKeyword("debugger")) {
            advance();
            consumeSemicolon();
            return new EmptyStatement();
        }
        if (isUsingDeclarationStart()) {
            return parseUsingDeclaration();
        }
        if (isAwaitUsingDeclarationStart()) {
            return parseAwaitUsingDeclaration();
        }
        if ((current().getType() == JsType.IDENTIFIER || keywordIsIdentifier()) && peek().getType() == JsType.OPERATOR
                && ":".equals(((JsOperator) peek()).getValue())) {
            return parseLabeledStatement();
        }
        return parseExpressionStatement();
    }

    protected LabeledStatement parseLabeledStatement() {
        rejectEscapedReserved();
        final var label = parseIdentifier();
        expectOperator(":");
        if (labels.containsKey(label.getName())) {
            throw new SyntaxErrorException("Label '" + label.getName() + "' has already been declared");
        }
        labels.put(label.getName(), labelsIterationStatement());
        try {
            return new LabeledStatement(label, parseNestedStatement());
        } finally {
            labels.remove(label.getName());
        }
    }

    protected BlockStatement parseBlock() {
        return inScope(this::parseBlockBody);
    }

    protected BlockStatement parseBlockBody() {
        expectSeparator('{');
        final var body = new ArrayList<Statement>();
        while (!isSeparator('}') && !atEnd()) {
            body.add(parseStatement());
        }
        expectSeparator('}');
        return new BlockStatement(body);
    }

    protected IfStatement parseIf() {
        expectKeyword("if");
        expectSeparator('(');
        final var test = parseExpression();
        expectSeparator(')');
        final var consequent = parseNestedStatement();
        Statement alternate = null;
        if (matchKeyword("else")) {
            alternate = parseNestedStatement();
        }
        return new IfStatement(test, consequent, alternate);
    }

    protected TryStatement parseTry() {
        expectKeyword("try");
        final var block = parseBlock();
        CatchClause handler = null;
        if (isKeyword("catch")) {
            handler = parseCatch();
        }
        BlockStatement finalizer = null;
        if (matchKeyword("finally")) {
            finalizer = parseBlock();
        }
        if (handler == null && finalizer == null) {
            throw error();
        }
        return new TryStatement(block, handler, finalizer);
    }

    protected CatchClause parseCatch() {
        expectKeyword("catch");
        return inScope(this::parseCatchRest);
    }

    protected CatchClause parseCatchRest() {
        JsNode param = null;
        if (matchSeparator('(')) {
            param = parseBindingTarget();
            expectSeparator(')');
            final var names = new ArrayList<String>();
            collectNames(param, names);
            for (final var name : names) {
                scope.declareCatchParam(name);
            }
        }
        return new CatchClause(param, parseBlockBody());
    }

    protected ThrowStatement parseThrow() {
        expectKeyword("throw");
        if (newlineBeforeCurrent()) {
            throw error();
        }
        final var argument = parseExpression();
        consumeSemicolon();
        return new ThrowStatement(argument);
    }

    protected SwitchStatement parseSwitch() {
        expectKeyword("switch");
        expectSeparator('(');
        final var discriminant = parseExpression();
        expectSeparator(')');
        expectSeparator('{');
        switchDepth++;
        final List<SwitchCase> cases;
        try {
            cases = inScope(() -> {
                final var parsed = new ArrayList<SwitchCase>();
                var seenDefault = false;
                while (!isSeparator('}') && !atEnd()) {
                    final var clause = parseSwitchCase();
                    if (clause.getTest() == null) {
                        if (seenDefault) {
                            throw new SyntaxErrorException("More than one default clause in switch statement");
                        }
                        seenDefault = true;
                    }
                    parsed.add(clause);
                }
                return parsed;
            });
        } finally {
            switchDepth--;
        }
        expectSeparator('}');
        return new SwitchStatement(discriminant, cases);
    }

    protected SwitchCase parseSwitchCase() {
        Expression test = null;
        if (matchKeyword("case")) {
            test = parseExpression();
        } else {
            expectKeyword("default");
        }
        expectOperator(":");
        final var consequent = new ArrayList<Statement>();
        while (!isKeyword("case") && !isKeyword("default") && !isSeparator('}') && !atEnd()) {
            if (isUsingDeclarationStart() || isAwaitUsingDeclarationStart()) {
                throw new SyntaxErrorException("using declaration is not allowed in a case or default clause");
            }
            consequent.add(parseStatement());
        }
        return new SwitchCase(test, consequent);
    }

    protected ReturnStatement parseReturn() {
        if (inStaticBlock || strictScriptGoal && !inFunctionBody) {
            throw new SyntaxErrorException("Illegal return statement");
        }
        expectKeyword("return");
        Expression argument = null;
        if (!isSeparator(';') && !isSeparator('}') && !atEnd() && !newlineBeforeCurrent()) {
            argument = parseExpression();
        }
        consumeSemicolon();
        return new ReturnStatement(argument);
    }

    protected BreakStatement parseBreak() {
        expectKeyword("break");
        final var label = current().getType() == JsType.IDENTIFIER && !newlineBeforeCurrent()
                ? parseIdentifier()
                : null;
        if (label == null) {
            if (iterationDepth == 0 && switchDepth == 0) {
                throw new SyntaxErrorException("Illegal break statement");
            }
        } else if (!labels.containsKey(label.getName())) {
            throw undefinedLabel(label);
        }
        consumeSemicolon();
        return new BreakStatement(label);
    }

    protected ContinueStatement parseContinue() {
        expectKeyword("continue");
        final var label = current().getType() == JsType.IDENTIFIER && !newlineBeforeCurrent()
                ? parseIdentifier()
                : null;
        if (label == null) {
            if (iterationDepth == 0) {
                throw new SyntaxErrorException("Illegal continue statement");
            }
        } else if (!Boolean.TRUE.equals(labels.get(label.getName()))) {
            throw undefinedLabel(label);
        }
        consumeSemicolon();
        return new ContinueStatement(label);
    }

    protected FunctionDeclaration parseFunctionDeclaration(boolean async, int start) {
        expectKeyword("function");
        final var generator = matchOperator("*");
        final var name = parseBindingIdentifier();
        declareBoundNames(name, !scope.isFunctionBoundary());
        final var parts = parseFunctionParts(generator, async);
        final var declaration = new FunctionDeclaration(name, parts.params(), parts.body(), async, generator);
        declaration.setSourceText(spanFrom(start));
        return declaration;
    }

    protected ExpressionStatement parseExpressionStatement() {
        final var expr = parseExpression();
        rejectCoverInitializedName(expr);
        consumeSemicolon();
        return new ExpressionStatement(expr);
    }
}
