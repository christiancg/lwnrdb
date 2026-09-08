package org.techhouse.simplejs.nodes;

public class AssignmentExpression extends Expression {
    private final String operator;
    private final JsNode target;
    private final Expression value;
    private final boolean targetParenthesized;

    public AssignmentExpression(String operator, JsNode target, Expression value) {
        this(operator, target, value, false);
    }

    public AssignmentExpression(String operator, JsNode target, Expression value, boolean targetParenthesized) {
        this.operator = operator;
        this.target = target;
        this.value = value;
        this.targetParenthesized = targetParenthesized;
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

    public boolean isTargetParenthesized() {
        return targetParenthesized;
    }
}
