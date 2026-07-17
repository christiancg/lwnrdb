package org.techhouse.simplejs.nodes;

public class ConditionalExpression extends Expression {
    private final Expression test;
    private final Expression consequent;
    private final Expression alternate;

    public ConditionalExpression(Expression test, Expression consequent, Expression alternate) {
        this.test = test;
        this.consequent = consequent;
        this.alternate = alternate;
    }

    public Expression getTest() {
        return test;
    }

    public Expression getConsequent() {
        return consequent;
    }

    public Expression getAlternate() {
        return alternate;
    }
}
