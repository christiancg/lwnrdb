package org.techhouse.simplejs.internal.parser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsBigInt;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RestElement;
import org.techhouse.simplejs.nodes.SequenceExpression;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.StringLiteral;

abstract class BindingProductions extends ModuleProductions {
    protected BindingProductions(String source, List<JsBaseElement> tokens, List<SourcePosition> positions,
            List<Boolean> newlineBefore, boolean strictScriptGoal) {
        super(source, tokens, positions, newlineBefore, strictScriptGoal);
    }

    @Override
    protected void rejectCoverInitializedName(Expression expr) {
        switch (expr) {
            case ObjectExpression object -> {
                for (final var member : object.getProperties()) {
                    if (member instanceof SpreadElement spread) {
                        rejectCoverInitializedName(spread.getArgument());
                    } else if (member instanceof Property property) {
                        if (property.isShorthand() && property.getValue() instanceof AssignmentExpression assign
                                && "=".equals(assign.getOperator())) {
                            throw new SyntaxErrorException("Invalid shorthand property initializer in object literal");
                        }
                        if (property.getValue() instanceof Expression value) {
                            rejectCoverInitializedName(value);
                        }
                    }
                }
            }
            case ArrayExpression array -> {
                for (final var element : array.getElements()) {
                    if (element instanceof SpreadElement spread) {
                        rejectCoverInitializedName(spread.getArgument());
                    } else if (element != null) {
                        rejectCoverInitializedName(element);
                    }
                }
            }
            case SequenceExpression sequence -> sequence.getExpressions().forEach(this::rejectCoverInitializedName);
            default -> {
            }
        }
    }

    protected List<JsNode> parseParams() {
        expectSeparator('(');
        final var params = new ArrayList<JsNode>();
        if (!isSeparator(')')) {
            do {
                if (isSeparator(')')) {
                    break;
                }
                if (matchOperator("...")) {
                    params.add(new RestElement(parseBindingTarget()));
                    break;
                }
                params.add(parseBindingElement());
            } while (matchSeparator(','));
        }
        expectSeparator(')');
        checkNoDuplicateParams(params);
        return params;
    }

    protected void checkNoDuplicateParams(List<JsNode> params) {
        final Set<String> names = new HashSet<>();
        for (final var param : params) {
            collectBoundNames(param, names);
        }
    }

    protected void collectBoundNames(JsNode node, Set<String> names) {
        switch (node) {
            case null -> {
            }
            case Identifier id -> {
                if (!names.add(id.getName())) {
                    throw new SyntaxErrorException("Duplicate parameter name not allowed in this context");
                }
            }
            case RestElement rest -> collectBoundNames(rest.getArgument(), names);
            case AssignmentPattern assignment -> collectBoundNames(assignment.getLeft(), names);
            case ArrayPattern array -> array.getElements().forEach(element -> collectBoundNames(element, names));
            case ObjectPattern object -> object.getProperties().forEach(prop -> collectBoundNames(prop, names));
            case Property prop -> collectBoundNames(prop.getValue(), names);
            default -> {
            }
        }
    }

    @Override
    protected Identifier parseIdentifier() {
        final var t = current();
        if (t.getType() == JsType.IDENTIFIER) {
            advance();
            return new Identifier(((JsIdentifier) t).getValue());
        }
        if (keywordIsIdentifier()) {
            advance();
            return new Identifier(((JsKeyword) t).getValue());
        }
        throw error();
    }

    protected Expression parseContextualIdentifier(String name) {
        if (peek().getType() == JsType.OPERATOR && "=>".equals(((JsOperator) peek()).getValue())) {
            if (newlineBeforePeek()) {
                throw error();
            }
            final var start = spanStart();
            advance();
            advance();
            return parseArrowBody(List.of(new Identifier(name)), false, start);
        }
        advance();
        return new Identifier(name);
    }

    @Override
    protected Identifier consumeUndefinedAsIdentifier() {
        advance();
        return new Identifier("undefined");
    }

    @Override
    protected JsNode parseBindingTarget() {
        if (isSeparator('[')) {
            return parseArrayPattern();
        }
        if (isSeparator('{')) {
            return parseObjectPattern();
        }
        return parseBindingIdentifier();
    }

    @Override
    protected Identifier parseBindingIdentifier() {
        rejectEscapedReserved();
        final var contextual = keywordIsIdentifier();
        final var id = parseIdentifier();
        if (!contextual) {
            validateBindingName(id.getName());
        }
        return id;
    }

    protected JsNode parseBindingElement() {
        final var target = parseBindingTarget();
        if (matchOperator("=")) {
            return new AssignmentPattern(target, parseAssignment());
        }
        return target;
    }

    protected ArrayPattern parseArrayPattern() {
        expectSeparator('[');
        final var elements = new ArrayList<JsNode>();
        while (!isSeparator(']')) {
            if (matchSeparator(',')) {
                elements.add(null);
                continue;
            }
            if (matchOperator("...")) {
                elements.add(new RestElement(parseBindingTarget()));
                break;
            }
            elements.add(parseBindingElement());
            if (!isSeparator(']')) {
                expectSeparator(',');
            }
        }
        expectSeparator(']');
        return new ArrayPattern(elements);
    }

    protected ObjectPattern parseObjectPattern() {
        expectSeparator('{');
        final var properties = new ArrayList<JsNode>();
        while (!isSeparator('}')) {
            if (matchOperator("...")) {
                properties.add(new RestElement(parseBindingTarget()));
                break;
            }
            properties.add(parseObjectPatternProperty());
            if (!isSeparator('}')) {
                expectSeparator(',');
            }
        }
        expectSeparator('}');
        return new ObjectPattern(properties);
    }

    protected Property parseObjectPatternProperty() {
        if (isSeparator('[')) {
            advance();
            final var key = withInAllowed(this::parseAssignment);
            expectSeparator(']');
            expectOperator(":");
            return new Property(key, parseBindingElement(), true, false);
        }
        final var t = current();
        final var contextualKeyword = t.getType() == JsType.KEYWORD && keywordIsIdentifier();
        final var shorthandIdentifier = t.getType() == JsType.IDENTIFIER || contextualKeyword;
        final Expression key = switch (t.getType()) {
            case STRING -> new StringLiteral(((JsString) t).getValue());
            case NUMBER -> new NumberLiteral(((JsNumber) t).getValue());
            case BIGINT -> new StringLiteral(((JsBigInt) t).getValue().toString());
            default -> literalPropertyKey(t);
        };
        advance();
        if (matchOperator(":")) {
            return new Property(key, parseBindingElement(), false, false);
        }
        if (!shorthandIdentifier) {
            throw error();
        }
        assert key instanceof Identifier;
        if (!contextualKeyword) {
            validateBindingName(((Identifier) key).getName());
        }
        if (matchOperator("=")) {
            return new Property(key, new AssignmentPattern(key, parseAssignment()), false, true);
        }
        return new Property(key, key, false, true);
    }
}
