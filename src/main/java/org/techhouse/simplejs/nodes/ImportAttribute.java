package org.techhouse.simplejs.nodes;

public class ImportAttribute extends JsNode {
    private final Expression key;
    private final StringLiteral value;

    public ImportAttribute(Expression key, StringLiteral value) {
        this.key = key;
        this.value = value;
    }

    public Expression getKey() {
        return key;
    }

    public StringLiteral getValue() {
        return value;
    }
}
