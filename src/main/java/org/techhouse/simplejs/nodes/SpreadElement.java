package org.techhouse.simplejs.nodes;

public class SpreadElement extends Expression {
    private final Expression argument;

    public SpreadElement(Expression argument) {
        this.argument = argument;
    }

    public Expression getArgument() {
        return argument;
    }

    @Override
    public NodeType getType() {
        return NodeType.SPREAD_ELEMENT;
    }
}
