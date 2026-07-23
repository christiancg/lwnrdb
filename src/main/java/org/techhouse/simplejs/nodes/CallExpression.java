package org.techhouse.simplejs.nodes;

import java.util.List;

public class CallExpression extends Expression {
    private final Expression callee;
    private final List<Expression> arguments;
    private final boolean optional;

    public CallExpression(Expression callee, List<Expression> arguments) {
        this(callee, arguments, false);
    }

    public CallExpression(Expression callee, List<Expression> arguments, boolean optional) {
        this.callee = callee;
        this.arguments = arguments;
        this.optional = optional;
    }

    public Expression getCallee() {
        return callee;
    }

    public List<Expression> getArguments() {
        return arguments;
    }

    public boolean isOptional() {
        return optional;
    }
}
