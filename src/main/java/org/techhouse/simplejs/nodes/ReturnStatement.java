package org.techhouse.simplejs.nodes;

public class ReturnStatement extends Statement {
    private final Expression argument;

    public ReturnStatement(Expression argument) {
        this.argument = argument;
    }

    public Expression getArgument() {
        return argument;
    }

    @Override
    public NodeType getType() {
        return NodeType.RETURN_STATEMENT;
    }
}
