package org.techhouse.simplejs.nodes;

public class AssignmentExpression extends Expression {
    private final String operator;
    private final JsNode target;
    private final Expression value;
    // True when the left-hand side was parenthesized in source (e.g. `(fn) = function(){}`): per
    // spec this makes the target a CoverParenthesizedExpression rather than a bare IdentifierRef, so
    // NamedEvaluation of an anonymous function/class value must be suppressed even though the parser's
    // "parens are transparent" cover-grammar still resolves the same Identifier/MemberExpression target.
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
