package org.techhouse.simplejs.nodes;

public class IfStatement extends Statement {
    private final Expression test;
    private final Statement consequent;
    private final Statement alternate;

    public IfStatement(Expression test, Statement consequent, Statement alternate) {
        this.test = test;
        this.consequent = consequent;
        this.alternate = alternate;
    }

    public Expression getTest() {
        return test;
    }

    public Statement getConsequent() {
        return consequent;
    }

    public Statement getAlternate() {
        return alternate;
    }
}
