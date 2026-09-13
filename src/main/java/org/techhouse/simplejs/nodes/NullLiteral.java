package org.techhouse.simplejs.nodes;

public class NullLiteral extends Expression {

    @Override
    public NodeType getType() {
        return NodeType.NULL_LITERAL;
    }
}
