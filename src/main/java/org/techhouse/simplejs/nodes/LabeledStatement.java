package org.techhouse.simplejs.nodes;

public class LabeledStatement extends Statement {
    private final Identifier label;
    private final Statement body;

    public LabeledStatement(Identifier label, Statement body) {
        this.label = label;
        this.body = body;
    }

    public Identifier getLabel() {
        return label;
    }

    public Statement getBody() {
        return body;
    }
}
