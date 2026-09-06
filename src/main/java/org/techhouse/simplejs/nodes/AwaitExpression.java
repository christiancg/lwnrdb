package org.techhouse.simplejs.nodes;

public class AwaitExpression extends Expression {
    private final Expression argument;

    public AwaitExpression(Expression argument) {
        this.argument = argument;
    }

    public Expression getArgument() {
        return argument;
    }
}
