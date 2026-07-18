package org.techhouse.simplejs.nodes;

public class ContinueStatement extends Statement {
    private final Identifier label;

    public ContinueStatement(Identifier label) {
        this.label = label;
    }

    public Identifier getLabel() {
        return label;
    }
}
