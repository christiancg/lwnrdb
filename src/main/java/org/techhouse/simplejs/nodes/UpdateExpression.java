package org.techhouse.simplejs.nodes;

public class UpdateExpression extends Expression {
    private final String operator;
    private final Expression argument;
    private final boolean prefix;

    public UpdateExpression(String operator, Expression argument, boolean prefix) {
        this.operator = operator;
        this.argument = argument;
        this.prefix = prefix;
    }

    public String getOperator() {
        return operator;
    }

    public Expression getArgument() {
        return argument;
    }

    public boolean isPrefix() {
        return prefix;
    }
}
