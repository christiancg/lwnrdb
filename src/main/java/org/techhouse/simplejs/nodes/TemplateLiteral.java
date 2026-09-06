package org.techhouse.simplejs.nodes;

import java.util.List;

public class TemplateLiteral extends Expression {
    private final List<String> quasis;
    private final List<String> rawQuasis;
    private final List<Expression> expressions;

    public TemplateLiteral(List<String> quasis, List<String> rawQuasis, List<Expression> expressions) {
        if (quasis.size() != expressions.size() + 1) {
            throw new IllegalArgumentException("A template literal must have exactly one more quasi than expressions");
        }
        if (rawQuasis.size() != quasis.size()) {
            throw new IllegalArgumentException("A template literal must have one raw quasi per cooked quasi");
        }
        this.quasis = quasis;
        this.rawQuasis = rawQuasis;
        this.expressions = expressions;
    }

    public List<String> getQuasis() {
        return quasis;
    }

    public List<String> getRawQuasis() {
        return rawQuasis;
    }

    public List<Expression> getExpressions() {
        return expressions;
    }
}
