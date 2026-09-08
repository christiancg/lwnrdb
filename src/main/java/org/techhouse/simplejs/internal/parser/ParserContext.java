package org.techhouse.simplejs.internal.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.lexer.LexerTables;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.ImportDeclaration;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.TemplateLiteral;

abstract class ParserContext extends GrammarPredicates {
    protected static final Set<String> CONTEXTUAL_KEYWORDS = Set.of("of", "async");

    protected final PatternConverter patterns = new PatternConverter(this);
    protected final Set<Expression> parenthesised = Collections.newSetFromMap(new IdentityHashMap<>());
    protected final Set<Expression> duplicateProto = Collections.newSetFromMap(new IdentityHashMap<>());
    protected DeclarationScope scope = new DeclarationScope(null, true);
    protected PrivateScope privateScope;
    protected final Map<String, Boolean> labels = new HashMap<>();
    protected final String source;
    protected int iterationDepth;
    protected int switchDepth;
    protected boolean inStaticBlock;
    protected boolean inGenerator;
    protected boolean inAsync = true;
    protected boolean awaitReserved;
    protected boolean superCallAllowed;
    protected boolean superPropertyAllowed;
    protected boolean classHasHeritage;
    protected final boolean strictScriptGoal;
    protected boolean inFunctionBody;
    protected boolean inNonArrowFunction;

    protected abstract Expression parsePrimary();

    protected abstract TemplateLiteral parseTemplate(JsTemplateString template, boolean tagged);

    protected abstract Identifier literalPropertyKey(JsBaseElement t);

    protected abstract void rejectCoverInitializedName(Expression expr);

    protected abstract boolean startsArrowFunction();

    protected abstract ArrowFunctionExpression parseArrowBody(List<JsNode> params, boolean async, int start);

    protected abstract FunctionParts parseFunctionParts(boolean generator, boolean async);

    protected abstract FunctionParts parseFunctionParts(boolean generator, boolean async, boolean superCall,
            boolean method);

    protected abstract Expression parseExpression();

    protected abstract Expression parseAssignment();

    protected abstract Statement parseStatement();

    protected abstract JsNode parseBindingTarget();

    protected abstract Identifier parseBindingIdentifier();

    protected abstract Identifier parseIdentifier();

    protected abstract Identifier consumeUndefinedAsIdentifier();

    protected abstract ClassDeclaration parseClassDeclaration();

    protected abstract ImportDeclaration parseImportDeclaration();

    protected abstract Statement parseExportDeclaration();

    protected abstract boolean isDynamicImportOrMeta();

    protected ParserContext(String source, List<JsBaseElement> tokens, List<SourcePosition> positions,
            List<Boolean> newlineBefore, boolean strictScriptGoal) {
        super(tokens, positions, newlineBefore);
        this.source = source;
        this.strictScriptGoal = strictScriptGoal;
    }

    protected int spanStart() {
        return source == null || positions == null ? -1 : positions.get(pos).getOffset();
    }

    protected SourcePosition positionAt(int index) {
        return positions == null ? null : positions.get(Math.min(index, positions.size() - 1));
    }

    protected String spanFrom(int start) {
        if (start < 0) {
            return null;
        }
        final var last = positions.get(Math.max(0, pos - 1));
        return source.substring(start, last.getOffset() + last.getLength());
    }

    protected <T> T inScope(Supplier<T> production) {
        scope = new DeclarationScope(scope, false);
        try {
            return production.get();
        } finally {
            scope = scope.getParent();
        }
    }

    protected <T> T inFunctionScope(List<JsNode> params, Supplier<T> production) {
        scope = new DeclarationScope(scope, true);
        try {
            for (final var param : params) {
                declareBoundNames(param, false);
            }
            return production.get();
        } finally {
            scope = scope.getParent();
        }
    }

    protected <T> T outsideGenerator(Supplier<T> production) {
        return inFunctionKind(false, production);
    }

    protected <T> T inFunctionKind(boolean async, Supplier<T> production) {
        final var wasInGenerator = inGenerator;
        final var wasInAsync = inAsync;
        setFunctionKind(false, async);
        try {
            return production.get();
        } finally {
            setFunctionKind(wasInGenerator, wasInAsync);
        }
    }

    protected void setFunctionKind(boolean generator, boolean async) {
        inGenerator = generator;
        inAsync = async;
    }

    protected void checkUseStrictWithSimpleParams(List<JsNode> params, BlockStatement body) {
        if (isSimpleParameterList(params) || !containsUseStrictDirective(body)) {
            return;
        }
        throw new SyntaxErrorException("Illegal 'use strict' directive in function with non-simple parameter list");
    }

    protected void declareBoundNames(JsNode target, boolean isLexical) {
        final var names = new ArrayList<String>();
        collectNames(target, names);
        for (final var name : names) {
            if (isLexical) {
                scope.declareLexical(name);
            } else {
                scope.declareVar(name);
            }
        }
    }

    protected void rejectTopLevelUsing(String kind) {
        if (strictScriptGoal && scope.getParent() == null) {
            throw new SyntaxErrorException("A " + kind + " declaration is not allowed at the top level of a script");
        }
    }

    protected void rejectOutsideFunctionCode(String construct) {
        if (strictScriptGoal && !inNonArrowFunction) {
            throw new SyntaxErrorException("'" + construct + "' is only allowed in function code");
        }
    }

    protected void rejectInScriptGoal(String construct) {
        if (strictScriptGoal) {
            throw new SyntaxErrorException(construct + " may only appear in a module");
        }
    }

    @SuppressWarnings("PMD.UnusedAssignment")
    protected <T> T inBreakableBoundary(Supplier<T> production) {
        final var enclosingLabels = new HashMap<>(labels);
        final var enclosingIterations = iterationDepth;
        final var enclosingSwitches = switchDepth;
        labels.clear();
        iterationDepth = 0;
        switchDepth = 0;
        try {
            return production.get();
        } finally {
            labels.clear();
            labels.putAll(enclosingLabels);
            iterationDepth = enclosingIterations;
            switchDepth = enclosingSwitches;
        }
    }

    protected boolean keywordIsIdentifier() {
        final var t = current();
        if (t.getType() != JsType.KEYWORD) {
            return false;
        }
        final var kw = ((JsKeyword) t).getValue();
        return CONTEXTUAL_KEYWORDS.contains(kw) || "await".equals(kw) && !awaitIsReserved();
    }

    protected boolean awaitIsReserved() {
        return awaitReserved;
    }

    protected boolean awaitIsIdentifierReference() {
        return !awaitIsReserved() && (!inAsync || !beginsExpression(peek()));
    }

    protected void rejectEscapedReserved() {
        if (current() instanceof JsIdentifier token && token.isEscaped() && isEscapeReserved(token.getValue())) {
            throw new SyntaxErrorException("Keyword must not contain escaped characters: " + token.getValue());
        }
    }

    protected boolean isEscapeReserved(String word) {
        if (CONTEXTUAL_KEYWORDS.contains(word)) {
            return false;
        }
        if ("await".equals(word)) {
            return awaitIsReserved();
        }
        return LexerTables.isReservedWord(word);
    }

    protected void validateBindingName(String name) {
        if ("await".equals(name)) {
            if (awaitIsReserved()) {
                throw new SyntaxErrorException("'await' cannot be used as a binding identifier in strict mode");
            }
            return;
        }
        if (ParserTables.STRICT_RESERVED.contains(name) || ParserTables.RESTRICTED_BINDINGS.contains(name)
                || isReservedWord(name)) {
            throw new SyntaxErrorException("'" + name + "' cannot be used as a binding identifier in strict mode");
        }
    }

    @SuppressWarnings("PMD.UnusedAssignment")
    protected <T> T withAwaitReserved(boolean reserved, Supplier<T> production) {
        final var wasAwaitReserved = awaitReserved;
        awaitReserved = reserved;
        try {
            return production.get();
        } finally {
            awaitReserved = wasAwaitReserved;
        }
    }
}
