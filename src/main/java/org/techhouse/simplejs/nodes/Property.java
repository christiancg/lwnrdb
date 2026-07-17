package org.techhouse.simplejs.nodes;

public class Property extends JsNode {
    private final Expression key;
    private final Expression value;
    private final boolean computed;
    private final boolean shorthand;

    public Property(Expression key, Expression value, boolean computed, boolean shorthand) {
        this.key = key;
        this.value = value;
        this.computed = computed;
        this.shorthand = shorthand;
    }

    public Expression getKey() {
        return key;
    }

    public Expression getValue() {
        return value;
    }

    public boolean isComputed() {
        return computed;
    }

    public boolean isShorthand() {
        return shorthand;
    }
}
