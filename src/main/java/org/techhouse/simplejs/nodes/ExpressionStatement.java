package org.techhouse.simplejs.nodes;

public class ExpressionStatement extends Statement {
    private final Expression expression;

    public ExpressionStatement(Expression expression) {
        this.expression = expression;
    }

    public Expression getExpression() {
        return expression;
    }

    @Override
    public NodeType getType() {
        return NodeType.EXPRESSION_STATEMENT;
    }
}
