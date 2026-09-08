package org.techhouse.simplejs.nodes;

import java.util.List;

public class SequenceExpression extends Expression {
    private final List<Expression> expressions;

    public SequenceExpression(List<Expression> expressions) {
        this.expressions = expressions;
    }

    public List<Expression> getExpressions() {
        return expressions;
    }
}
