package org.techhouse.simplejs.nodes;

public class BreakStatement extends Statement {
    private final Identifier label;

    public BreakStatement(Identifier label) {
        this.label = label;
    }

    public Identifier getLabel() {
        return label;
    }
}
