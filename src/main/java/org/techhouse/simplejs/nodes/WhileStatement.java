package org.techhouse.simplejs.nodes;

public class WhileStatement extends Statement {
    private final Expression test;
    private final Statement body;

    public WhileStatement(Expression test, Statement body) {
        this.test = test;
        this.body = body;
    }

    public Expression getTest() {
        return test;
    }

    public Statement getBody() {
        return body;
    }
}
