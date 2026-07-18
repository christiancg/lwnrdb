package org.techhouse.simplejs.nodes;

public class AssignmentExpression extends Expression {
    private final String operator;
    private final JsNode target;
    private final Expression value;

    public AssignmentExpression(String operator, JsNode target, Expression value) {
        this.operator = operator;
        this.target = target;
        this.value = value;
    }

    public String getOperator() {
        return operator;
    }

    public JsNode getTarget() {
        return target;
    }

    public Expression getValue() {
        return value;
    }
}
