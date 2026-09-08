package org.techhouse.simplejs.internal.parser;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.DoWhileStatement;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ForInStatement;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.VariableDeclarator;
import org.techhouse.simplejs.nodes.WhileStatement;

abstract class LoopProductions extends ParserContext {
    protected LoopProductions(String source, List<JsBaseElement> tokens, List<SourcePosition> positions,
            List<Boolean> newlineBefore, boolean strictScriptGoal) {
        super(source, tokens, positions, newlineBefore, strictScriptGoal);
    }

    protected boolean isUsingDeclarationStart() {
        return isContextualKeyword("using") && identifierLikeAt(1) && (isNotKeywordAt(1)
                || peekAt(2).getType() == JsType.OPERATOR && "=".equals(((JsOperator) peekAt(2)).getValue()));
    }

    protected boolean isAwaitUsingDeclarationStart() {
        return isKeyword("await") && peek().getType() == JsType.IDENTIFIER
                && "using".equals(((JsIdentifier) peek()).getValue()) && identifierLikeAt(2);
    }

    protected boolean identifierLikeAt(int offset) {
        final var t = peekAt(offset);
        if (t.getType() == JsType.IDENTIFIER) {
            return true;
        }
        if (t.getType() != JsType.KEYWORD) {
            return false;
        }
        final var kw = ((JsKeyword) t).getValue();
        return CONTEXTUAL_KEYWORDS.contains(kw) || "await".equals(kw) && !awaitIsReserved();
    }

    protected boolean isNotKeywordAt(int offset) {
        final var t = peekAt(offset);
        return t.getType() != JsType.KEYWORD || !((JsKeyword) t).getValue().equals("of");
    }

    protected VariableDeclaration parseUsingDeclaration() {
        rejectTopLevelUsing("using");
        expectContextualKeyword("using");
        return new VariableDeclaration("using", parseUsingDeclarators());
    }

    protected VariableDeclaration parseAwaitUsingDeclaration() {
        rejectTopLevelUsing("await using");
        expectKeyword("await");
        expectContextualKeyword("using");
        return new VariableDeclaration("await using", parseUsingDeclarators());
    }

    protected List<VariableDeclarator> parseUsingDeclarators() {
        final var declarations = new ArrayList<VariableDeclarator>();
        do {
            final var id = parseBindingIdentifier();
            expectOperator("=");
            declareBoundNames(id, true);
            declarations.add(new VariableDeclarator(id, parseAssignment()));
        } while (matchSeparator(','));
        consumeSemicolon();
        return declarations;
    }

    protected boolean labelsIterationStatement() {
        var offset = 0;
        while (peekAt(offset).getType() == JsType.IDENTIFIER && peekAt(offset + 1).getType() == JsType.OPERATOR
                && ":".equals(((JsOperator) peekAt(offset + 1)).getValue())) {
            offset += 2;
        }
        final var statement = peekAt(offset);
        return statement.getType() == JsType.KEYWORD
                && ParserTables.ITERATION_KEYWORDS.contains(((JsKeyword) statement).getValue());
    }

    protected Statement parseLoopBody() {
        iterationDepth++;
        try {
            return parseNestedStatement();
        } finally {
            iterationDepth--;
        }
    }

    protected Statement parseNestedStatement() {
        if (isDeclarationStart()) {
            throw new SyntaxErrorException("Declaration is not allowed in statement position");
        }
        return parseStatement();
    }

    protected boolean isDeclarationStart() {
        if (isKeyword("function") || isKeyword("class") || isKeyword("let") || isKeyword("const")) {
            return true;
        }
        if (isKeyword("async") && !newlineBeforePeek() && peek().getType() == JsType.KEYWORD
                && "function".equals(((JsKeyword) peek()).getValue())) {
            return true;
        }
        return isUsingDeclarationStart() || isAwaitUsingDeclarationStart();
    }

    protected VariableDeclaration parseVariableDeclaration() {
        final var kind = ((JsKeyword) advance()).getValue();
        final var declarations = new ArrayList<VariableDeclarator>();
        do {
            final var id = "var".equals(kind) && current().getType() == JsType.UNDEFINED
                    ? consumeUndefinedAsIdentifier()
                    : parseBindingTarget();
            Expression init = null;
            if (matchOperator("=")) {
                init = parseAssignment();
            }
            if (init == null && "const".equals(kind)) {
                throw new SyntaxErrorException("Missing initializer in const declaration");
            }
            declareBoundNames(id, !"var".equals(kind));
            declarations.add(new VariableDeclarator(id, init));
        } while (matchSeparator(','));
        consumeSemicolon();
        return new VariableDeclaration(kind, declarations);
    }

    protected WhileStatement parseWhile() {
        expectKeyword("while");
        expectSeparator('(');
        final var test = parseExpression();
        expectSeparator(')');
        final var body = parseLoopBody();
        return new WhileStatement(test, body);
    }

    protected DoWhileStatement parseDoWhile() {
        expectKeyword("do");
        final var body = parseLoopBody();
        expectKeyword("while");
        expectSeparator('(');
        final var test = parseExpression();
        expectSeparator(')');
        consumeDoWhileSemicolon();
        return new DoWhileStatement(body, test);
    }

    protected Statement parseFor() {
        return inScope(this::parseForRest);
    }

    protected Statement parseForRest() {
        expectKeyword("for");
        final var isAwait = isKeyword("await");
        if (isAwait) {
            advance();
        }
        expectSeparator('(');
        if (isSeparator(';')) {
            if (isAwait) {
                throw error();
            }
            return parseClassicForRest(null);
        }
        final var asyncHead = !isAwait && isKeyword("async");
        final var init = parseForHeaderLeft();
        if (isKeyword("in") || isKeyword("of")) {
            if (asyncHead && isKeyword("of") && init instanceof Identifier id && "async".equals(id.getName())) {
                throw new SyntaxErrorException("The left-hand side of a for-of loop may not be the token 'async'");
            }
            return parseForInOf(init, isAwait);
        }
        if (isAwait) {
            throw error();
        }
        return parseClassicForRest(init);
    }

    protected JsNode parseForHeaderLeft() {
        if (isKeyword("var") || isKeyword("let") || isKeyword("const")) {
            return parseForVariableDeclaration();
        }
        if (isUsingDeclarationStart()) {
            expectContextualKeyword("using");
            return parseForUsingDeclaration("using");
        }
        if (isAwaitUsingDeclarationStart()) {
            expectKeyword("await");
            expectContextualKeyword("using");
            return parseForUsingDeclaration("await using");
        }
        return withNoIn(this::parseExpression);
    }

    protected VariableDeclaration parseForUsingDeclaration(String kind) {
        final var declarations = new ArrayList<VariableDeclarator>();
        do {
            final var id = parseBindingIdentifier();
            Expression init = null;
            if (matchOperator("=")) {
                init = withNoIn(this::parseAssignment);
            }
            declareBoundNames(id, true);
            declarations.add(new VariableDeclarator(id, init));
        } while (matchSeparator(','));
        if (!isKeyword("of") && declarations.stream().anyMatch(declarator -> declarator.getInit() == null)) {
            throw new SyntaxErrorException("Missing initializer in " + kind + " declaration");
        }
        return new VariableDeclaration(kind, declarations);
    }

    protected VariableDeclaration parseForVariableDeclaration() {
        final var kind = ((JsKeyword) advance()).getValue();
        return withNoIn(() -> {
            final var declarations = new ArrayList<VariableDeclarator>();
            do {
                final var id = parseBindingTarget();
                Expression init = null;
                if (matchOperator("=")) {
                    init = parseAssignment();
                }
                declareBoundNames(id, !"var".equals(kind));
                declarations.add(new VariableDeclarator(id, init));
            } while (matchSeparator(','));
            return new VariableDeclaration(kind, declarations);
        });
    }

    protected Statement parseForInOf(JsNode left, boolean isAwait) {
        final var target = left instanceof ArrayExpression || left instanceof ObjectExpression
                ? patterns.toAssignmentPattern((Expression) left)
                : left;
        final var isOf = "of".equals(((JsKeyword) current()).getValue());
        patterns.validateForInOfTarget(target, isOf);
        if (isAwait && !isOf) {
            throw error();
        }
        advance();
        final var right = isOf ? parseAssignment() : parseExpression();
        expectSeparator(')');
        final var body = parseLoopBody();
        return isOf ? new ForOfStatement(target, right, body, isAwait) : new ForInStatement(target, right, body);
    }

    protected ForStatement parseClassicForRest(JsNode init) {
        if (init instanceof VariableDeclaration declaration && "const".equals(declaration.getKind())) {
            for (final var declarator : declaration.getDeclarations()) {
                if (declarator.getInit() == null) {
                    throw new SyntaxErrorException("Missing initializer in const declaration");
                }
            }
        }
        expectSeparator(';');
        Expression test = null;
        if (!isSeparator(';')) {
            test = parseExpression();
        }
        expectSeparator(';');
        Expression update = null;
        if (!isSeparator(')')) {
            update = parseExpression();
        }
        expectSeparator(')');
        final var body = parseLoopBody();
        return new ForStatement(init, test, update, body);
    }
}
