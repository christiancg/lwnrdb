package org.techhouse.simplejs.nodes;

public class UpdateExpression extends Expression {
    private final String operator;
    private final boolean increment;
    private final Expression argument;
    private final boolean prefix;

    public UpdateExpression(String operator, Expression argument, boolean prefix) {
        this.operator = operator;
        this.increment = "++".equals(operator);
        this.argument = argument;
        this.prefix = prefix;
    }

    public String getOperator() {
        return operator;
    }

    public boolean isIncrement() {
        return increment;
    }

    public Expression getArgument() {
        return argument;
    }

    public boolean isPrefix() {
        return prefix;
    }

    @Override
    public NodeType getType() {
        return NodeType.UPDATE_EXPRESSION;
    }
}
