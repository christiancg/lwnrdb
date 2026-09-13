package org.techhouse.simplejs.nodes;

public class ThisExpression extends Expression {

    @Override
    public NodeType getType() {
        return NodeType.THIS_EXPRESSION;
    }
}
