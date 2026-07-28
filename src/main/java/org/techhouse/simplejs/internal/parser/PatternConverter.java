package org.techhouse.simplejs.internal.parser;

import java.util.ArrayList;
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
        if (left instanceof Identifier || left instanceof MemberExpression) {
            return left;
        }
        if ("=".equals(op) && (left instanceof ArrayExpression || left instanceof ObjectExpression)) {
            return toAssignmentPattern(left);
        }
        throw stream.error();
    }

    public JsNode toAssignmentPattern(Expression expr) {
        if (expr instanceof Identifier || expr instanceof MemberExpression) {
            return expr;
        }
        if (expr instanceof ArrayExpression array) {
            final var elements = new ArrayList<JsNode>();
            for (final var element : array.getElements()) {
                elements.add(toPatternElement(element));
            }
            return new ArrayPattern(elements);
        }
        if (expr instanceof ObjectExpression object) {
            final var properties = new ArrayList<JsNode>();
            for (final var property : object.getProperties()) {
                properties.add(toPatternProperty(property));
            }
            return new ObjectPattern(properties);
        }
        throw stream.error();
    }

    public void validateForInOfTarget(JsNode left) {
        if (left instanceof VariableDeclaration declaration) {
            final var declarations = declaration.getDeclarations();
            if (declarations.size() != 1 || declarations.getFirst().getInit() != null) {
                throw stream.error();
            }
            return;
        }
        if (!(left instanceof Identifier) && !(left instanceof MemberExpression) && !(left instanceof ArrayPattern)
                && !(left instanceof ObjectPattern)) {
            throw stream.error();
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
