package org.techhouse.simplejs.internal.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsBigInt;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsPrivateIdentifier;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.nodes.ClassBody;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ClassExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.FieldDefinition;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.MethodDefinition;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.RestElement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StaticBlock;
import org.techhouse.simplejs.nodes.StringLiteral;

abstract class ClassProductions extends ExpressionProductions {
    protected ClassProductions(String source, List<JsBaseElement> tokens, List<SourcePosition> positions,
            List<Boolean> newlineBefore, boolean strictScriptGoal) {
        super(source, tokens, positions, newlineBefore, strictScriptGoal);
    }

    @Override
    protected ClassDeclaration parseClassDeclaration() {
        final var start = spanStart();
        expectKeyword("class");
        final var id = parseBindingIdentifier();
        declareBoundNames(id, true);
        final var superClass = parseClassHeritage();
        final var declaration = new ClassDeclaration(id, superClass, parseClassBody(superClass != null));
        declaration.setSourceText(spanFrom(start));
        return declaration;
    }

    protected ClassExpression parseClassExpression() {
        final var start = spanStart();
        expectKeyword("class");
        Identifier id = null;
        if (identifierLikeAt(0) && isNotKeywordAt(0)) {
            id = parseBindingIdentifier();
        }
        final var superClass = parseClassHeritage();
        final var expression = new ClassExpression(id, superClass, parseClassBody(superClass != null));
        expression.setSourceText(spanFrom(start));
        return expression;
    }

    protected Expression parseClassHeritage() {
        if (!matchKeyword("extends")) {
            return null;
        }
        if (startsArrowFunction()) {
            throw error();
        }
        return parseCallMember();
    }

    protected ClassBody parseClassBody(boolean hasHeritage) {
        final var enclosing = privateScope;
        privateScope = new PrivateScope(enclosing);
        try {
            final var body = parseClassBodyMembers(hasHeritage);
            privateScope.resolve();
            return body;
        } finally {
            privateScope = enclosing;
        }
    }

    protected ClassBody parseClassBodyMembers(boolean hasHeritage) {
        final var wasClassHasHeritage = classHasHeritage;
        final var wasInNonArrowFunction = inNonArrowFunction;
        final var wasSuperPropertyAllowed = superPropertyAllowed;
        classHasHeritage = hasHeritage;
        inNonArrowFunction = true;
        superPropertyAllowed = true;
        expectSeparator('{');
        final var members = new ArrayList<JsNode>();
        final Map<String, Set<String>> privateNames = new HashMap<>();
        var seenConstructor = false;
        try {
            while (!isSeparator('}') && !atEnd()) {
                if (matchSeparator(';')) {
                    continue;
                }
                final var member = parseClassMember(privateNames);
                if (member instanceof MethodDefinition method && "constructor".equals(method.getKind())) {
                    if (seenConstructor) {
                        throw new SyntaxErrorException("A class may only have one constructor");
                    }
                    seenConstructor = true;
                }
                members.add(member);
            }
        } finally {
            classHasHeritage = wasClassHasHeritage;
            inNonArrowFunction = wasInNonArrowFunction;
            superPropertyAllowed = wasSuperPropertyAllowed;
        }
        expectSeparator('}');
        return new ClassBody(members);
    }

    protected JsNode parseClassMember(Map<String, Set<String>> privateNames) {
        final var isStatic = matchContextualModifier("static");
        if (isStatic && isSeparator('{')) {
            return parseStaticBlock();
        }
        final var start = spanStart();
        final var async = matchAsyncMethodModifier();
        final var generator = matchOperator("*");
        var kind = "method";
        if (!async && !generator) {
            if (matchContextualModifier("get")) {
                kind = "get";
            } else if (matchContextualModifier("set")) {
                kind = "set";
            }
        }
        final var memberKey = parseClassMemberKey();
        if (isSeparator('(')) {
            checkStaticMethodName(memberKey, isStatic);
            final var resolvedKind = resolveMethodKind(kind, memberKey, isStatic, async, generator);
            final var parts = parseFunctionParts(generator, async,
                    "constructor".equals(resolvedKind) && classHasHeritage, true);
            checkAccessorParams(resolvedKind, parts.params());
            final var value = new FunctionExpression(null, parts.params(), parts.body(), async, generator);
            value.setSourceText(spanFrom(start));
            declarePrivateName(privateNames, memberKey, resolvedKind, isStatic);
            return new MethodDefinition(memberKey.key(), value, resolvedKind, isStatic, memberKey.computed());
        }
        if (!"method".equals(kind) || async || generator) {
            throw error();
        }
        checkFieldName(memberKey, isStatic);
        declarePrivateName(privateNames, memberKey, "field", isStatic);
        Expression value = null;
        if (matchOperator("=")) {
            value = outsideGenerator(this::parseAssignment);
            if (containsArgumentsOrSuperCall(value)) {
                throw new SyntaxErrorException(
                        "'arguments' and a bare 'super' call are not allowed in a class field initializer");
            }
        }
        consumeSemicolon();
        return new FieldDefinition(memberKey.key(), value, isStatic, memberKey.computed());
    }

    protected void checkAccessorParams(String kind, List<JsNode> params) {
        if ("get".equals(kind) && !params.isEmpty()) {
            throw new SyntaxErrorException("Getter must not have any formal parameters");
        }
        if ("set".equals(kind) && (params.size() != 1 || params.getFirst() instanceof RestElement)) {
            throw new SyntaxErrorException("Setter must have exactly one formal parameter");
        }
    }

    protected void checkStaticMethodName(MemberKey memberKey, boolean isStatic) {
        if (isStatic && "prototype".equals(propName(memberKey))) {
            throw new SyntaxErrorException("Classes may not have a static method named 'prototype'");
        }
    }

    protected void checkFieldName(MemberKey memberKey, boolean isStatic) {
        final var name = propName(memberKey);
        if (name == null) {
            return;
        }
        if ("constructor".equals(name)) {
            throw new SyntaxErrorException("Classes may not have a field named 'constructor'");
        }
        if (isStatic && "prototype".equals(name)) {
            throw new SyntaxErrorException("Classes may not have a static field named 'prototype'");
        }
    }

    protected void declarePrivateName(Map<String, Set<String>> privateNames, MemberKey memberKey, String kind,
            boolean isStatic) {
        if (!(memberKey.key() instanceof PrivateIdentifier priv)) {
            return;
        }
        if ("constructor".equals(priv.getName())) {
            throw new SyntaxErrorException("Classes may not have a private member named #constructor");
        }
        privateScope.declare(priv.getName());
        final var existing = privateNames.computeIfAbsent(priv.getName(), _ -> new HashSet<>());
        final var isDuplicate = switch (kind) {
            case "get" -> existing.contains("get") || existing.contains("field") || existing.contains("method");
            case "set" -> existing.contains("set") || existing.contains("field") || existing.contains("method");
            default -> !existing.isEmpty();
        };
        if (isDuplicate) {
            throw new SyntaxErrorException("Duplicate private name #" + priv.getName());
        }
        if (!existing.isEmpty() && existing.contains(isStatic ? "instance" : "static")) {
            throw new SyntaxErrorException(
                    "A private getter and setter named #" + priv.getName() + " must agree on 'static'");
        }
        existing.add(kind);
        existing.add(isStatic ? "static" : "instance");
    }

    protected StaticBlock parseStaticBlock() {
        return inFunctionScope(List.of(), () -> inBreakableBoundary(() -> {
            final var wasInStaticBlock = inStaticBlock;
            inStaticBlock = true;
            try {
                return parseStaticBlockBody();
            } finally {
                inStaticBlock = wasInStaticBlock;
            }
        }));
    }

    protected StaticBlock parseStaticBlockBody() {
        final var wasAwaitReserved = awaitReserved;
        awaitReserved = true;
        try {
            return parseStaticBlockStatements();
        } finally {
            awaitReserved = wasAwaitReserved;
        }
    }

    protected StaticBlock parseStaticBlockStatements() {
        expectSeparator('{');
        final var body = outsideGenerator(() -> {
            final var statements = new ArrayList<Statement>();
            while (!isSeparator('}') && !atEnd()) {
                statements.add(parseStatement());
            }
            return statements;
        });
        expectSeparator('}');
        return new StaticBlock(body);
    }

    protected String resolveMethodKind(String kind, MemberKey memberKey, boolean isStatic, boolean async,
            boolean generator) {
        if (isStatic || !isNamedConstructor(memberKey)) {
            return "method".equals(kind) ? "method" : kind;
        }
        if (async || generator || !"method".equals(kind)) {
            throw new SyntaxErrorException("A class constructor may not be a generator, async or an accessor");
        }
        return "constructor";
    }

    protected boolean isNamedConstructor(MemberKey memberKey) {
        if (memberKey.computed()) {
            return false;
        }
        return memberKey.key() instanceof Identifier id && "constructor".equals(id.getName())
                || memberKey.key() instanceof StringLiteral str && "constructor".equals(str.getValue());
    }

    protected MemberKey parseClassMemberKey() {
        if (isSeparator('[')) {
            advance();
            final var key = withInAllowed(this::parseAssignment);
            expectSeparator(']');
            return new MemberKey(key, true);
        }
        final var t = current();
        final Expression key = switch (t.getType()) {
            case STRING -> new StringLiteral(((JsString) t).getValue());
            case NUMBER -> new NumberLiteral(((JsNumber) t).getValue());
            case BIGINT -> new StringLiteral(((JsBigInt) t).getValue().toString());
            case PRIVATE_IDENTIFIER -> new PrivateIdentifier(((JsPrivateIdentifier) t).getValue());
            default -> literalPropertyKey(t);
        };
        advance();
        return new MemberKey(key, false);
    }

    protected boolean matchContextualModifier(String name) {
        final var t = current();
        if (t.getType() != JsType.IDENTIFIER || !((JsIdentifier) t).getValue().equals(name)
                || ((JsIdentifier) t).isEscaped()) {
            return false;
        }
        final var next = peek();
        if (next.getType() == JsType.OPERATOR && "=".equals(((JsOperator) next).getValue())) {
            return false;
        }
        if (("get".equals(name) || "set".equals(name)) && next.getType() == JsType.OPERATOR
                && "*".equals(((JsOperator) next).getValue())) {
            return false;
        }
        if (next.getType() == JsType.SEPARATOR) {
            final char c = ((JsSeparator) next).getValue();
            if (c == '(' || c == ';' || c == '}') {
                return false;
            }
        }
        advance();
        return true;
    }

    protected boolean matchAsyncMethodModifier() {
        final var t = current();
        if (t.getType() != JsType.KEYWORD || !"async".equals(((JsKeyword) t).getValue())) {
            return false;
        }
        final var next = peek();
        if (next.getType() == JsType.OPERATOR && "=".equals(((JsOperator) next).getValue())) {
            return false;
        }
        if (next.getType() == JsType.SEPARATOR) {
            final char c = ((JsSeparator) next).getValue();
            if (c == '(' || c == ';' || c == '}') {
                return false;
            }
        }
        advance();
        return true;
    }

}
