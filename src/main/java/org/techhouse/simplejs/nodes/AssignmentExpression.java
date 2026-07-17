package org.techhouse.simplejs.nodes;

public class AssignmentExpression extends Expression {
    private final String operator;
    private final Expression target;
    private final Expression value;

    public AssignmentExpression(String operator, Expression target, Expression value) {
        this.operator = operator;
        this.target = target;
        this.value = value;
    }

    public String getOperator() {
        return operator;
    }

    public Expression getTarget() {
        return target;
    }

    public Expression getValue() {
        return value;
    }
}
