package org.techhouse.simplejs.nodes;

import java.util.List;

public class NewExpression extends Expression {
    private final Expression callee;
    private final List<Expression> arguments;

    public NewExpression(Expression callee, List<Expression> arguments) {
        this.callee = callee;
        this.arguments = arguments;
    }

    public Expression getCallee() {
        return callee;
    }

    public List<Expression> getArguments() {
        return arguments;
    }
}
