package org.techhouse.simplejs.nodes;

public class DoWhileStatement extends Statement {
    private final Statement body;
    private final Expression test;

    public DoWhileStatement(Statement body, Expression test) {
        this.body = body;
        this.test = test;
    }

    public Statement getBody() {
        return body;
    }

    public Expression getTest() {
        return test;
    }
}
