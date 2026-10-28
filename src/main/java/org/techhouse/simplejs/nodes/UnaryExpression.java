package org.techhouse.simplejs.nodes;

public class UnaryExpression extends Expression {
    private final String operator;
    private final UnaryOperator kind;
    private final Expression argument;
    private final boolean prefix;

    public UnaryExpression(String operator, Expression argument, boolean prefix) {
        this.operator = operator;
        this.kind = UnaryOperator.of(operator);
        this.argument = argument;
        this.prefix = prefix;
    }

    public String getOperator() {
        return operator;
    }

    public UnaryOperator kind() {
        return kind;
    }

    public Expression getArgument() {
        return argument;
    }

    public boolean isPrefix() {
        return prefix;
    }

    @Override
    public NodeType getType() {
        return NodeType.UNARY_EXPRESSION;
    }
}
