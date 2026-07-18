package org.techhouse.simplejs.nodes;

public class ThrowStatement extends Statement {
    private final Expression argument;

    public ThrowStatement(Expression argument) {
        this.argument = argument;
    }

    public Expression getArgument() {
        return argument;
    }
}
