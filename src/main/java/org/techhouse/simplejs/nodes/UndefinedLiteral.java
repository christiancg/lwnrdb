package org.techhouse.simplejs.nodes;

public class UndefinedLiteral extends Expression {

    @Override
    public NodeType getType() {
        return NodeType.UNDEFINED_LITERAL;
    }
}
