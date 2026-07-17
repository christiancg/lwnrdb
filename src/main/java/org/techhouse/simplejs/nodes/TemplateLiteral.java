package org.techhouse.simplejs.nodes;

import java.util.List;

public class TemplateLiteral extends Expression {
    private final List<String> quasis;
    private final List<Expression> expressions;

    public TemplateLiteral(List<String> quasis, List<Expression> expressions) {
        if (quasis.size() != expressions.size() + 1) {
            throw new IllegalArgumentException("A template literal must have exactly one more quasi than expressions");
        }
        this.quasis = quasis;
        this.expressions = expressions;
    }

    public List<String> getQuasis() {
        return quasis;
    }

    public List<Expression> getExpressions() {
        return expressions;
    }
}
