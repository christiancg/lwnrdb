package org.techhouse.simplejs.internal.parser;

import java.util.ArrayList;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RestElement;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.VariableDeclaration;

// Cover-grammar reinterpretation: an array/object expression parsed on an assignment LHS (or a
// for-in/for-of target) is turned into the equivalent binding pattern once the `=` / `in` / `of`
// proves the intent. Pure AST transforms; the only parser state consulted is the shared
// TokenStream, used to raise a positioned error on a non-reinterpretable node.
public final class PatternConverter {
    private final TokenStream stream;

    public PatternConverter(TokenStream stream) {
        this.stream = stream;
    }

    public JsNode resolveAssignmentTarget(Expression left, String op) {
        return switch (left) {
            case Identifier id -> {
                checkAssignable(id);
                yield id;
            }
            case MemberExpression member -> {
                checkNotOptionalChain(member);
                yield member;
            }
            case ArrayExpression ignored when "=".equals(op) -> toAssignmentPattern(left);
            case ObjectExpression ignored when "=".equals(op) -> toAssignmentPattern(left);
            default -> throw stream.error();
        };
    }

    private void checkAssignable(Identifier id) {
        if (ParserTables.RESTRICTED_BINDINGS.contains(id.getName())) {
            throw new SyntaxErrorException("'" + id.getName() + "' cannot be assigned to in strict mode");
        }
    }

    public JsNode toAssignmentPattern(Expression expr) {
        return switch (expr) {
            case Identifier id -> {
                checkAssignable(id);
                yield id;
            }
            case MemberExpression member -> {
                checkNotOptionalChain(member);
                yield member;
            }
            case ArrayExpression array -> {
                final var elements = new ArrayList<JsNode>();
                for (final var element : array.getElements()) {
                    checkRestIsLast(element, elements.size(), array.getElements().size(), array.hasTrailingComma());
                    elements.add(toPatternElement(element));
                }
                yield new ArrayPattern(elements);
            }
            case ObjectExpression object -> {
                final var properties = new ArrayList<JsNode>();
                for (final var property : object.getProperties()) {
                    checkRestIsLast(property, properties.size(), object.getProperties().size(),
                            object.hasTrailingComma());
                    properties.add(toPatternProperty(property));
                }
                yield new ObjectPattern(properties);
            }
            default -> throw stream.error();
        };
    }

    public void validateForInOfTarget(JsNode left, boolean isOf) {
        if (left instanceof VariableDeclaration declaration) {
            final var declarations = declaration.getDeclarations();
            if (declarations.size() != 1 || declarations.getFirst().getInit() != null) {
                throw stream.error();
            }
            if (!isOf && ParserTables.USING_KINDS.contains(declaration.getKind())) {
                throw stream.error();
            }
            return;
        }
        if (!(left instanceof Identifier) && !(left instanceof MemberExpression) && !(left instanceof ArrayPattern)
                && !(left instanceof ObjectPattern)) {
            throw stream.error();
        }
    }

    // AssignmentRestElement/AssignmentRestProperty is the final element of its pattern, and a trailing
    // comma after it is an elision the grammar has no production for.
    private void checkRestIsLast(JsNode element, int index, int total, boolean trailingComma) {
        if (element instanceof SpreadElement && (index != total - 1 || trailingComma)) {
            throw stream.error();
        }
    }

    private void checkNotOptionalChain(MemberExpression member) {
        var node = member;
        while (true) {
            if (node.isOptional()) {
                throw stream.error();
            }
            if (node.getObject() instanceof MemberExpression parent) {
                node = parent;
            } else {
                return;
            }
        }
    }

    private JsNode toPatternElement(Expression element) {
        if (element == null) {
            return null;
        }
        if (element instanceof SpreadElement spread) {
            return new RestElement(toAssignmentPattern(spread.getArgument()));
        }
        return toPatternDefault(element);
    }

    private JsNode toPatternProperty(JsNode property) {
        if (property instanceof SpreadElement spread) {
            return new RestElement(toAssignmentPattern(spread.getArgument()));
        }
        final var prop = (Property) property;
        final var value = prop.getValue();
        if (!(value instanceof Expression valueExpr)) {
            throw stream.error();
        }
        return new Property(prop.getKey(), toPatternDefault(valueExpr), prop.isComputed(), prop.isShorthand());
    }

    private JsNode toPatternDefault(Expression expr) {
        if (expr instanceof AssignmentExpression assignment && "=".equals(assignment.getOperator())) {
            return new AssignmentPattern(toBindingTarget(assignment.getTarget()), assignment.getValue());
        }
        return toAssignmentPattern(expr);
    }

    // An assignment LHS may already be a reinterpreted pattern (e.g. `{a: [x] = d}`); only a raw
    // array/object expression still needs converting.
    private JsNode toBindingTarget(JsNode node) {
        if (node instanceof Expression expr) {
            return toAssignmentPattern(expr);
        }
        return node;
    }
}
