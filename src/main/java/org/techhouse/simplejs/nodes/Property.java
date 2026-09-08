package org.techhouse.simplejs.nodes;

public class Property extends JsNode {
    private final Expression key;
    private final JsNode value;
    private final boolean computed;
    private final boolean shorthand;
    private final String kind;

    public Property(Expression key, JsNode value, boolean computed, boolean shorthand) {
        this(key, value, computed, shorthand, "init");
    }

    public Property(Expression key, JsNode value, boolean computed, boolean shorthand, String kind) {
        this.key = key;
        this.value = value;
        this.computed = computed;
        this.shorthand = shorthand;
        this.kind = kind;
    }

    public Expression getKey() {
        return key;
    }

    public JsNode getValue() {
        return value;
    }

    public boolean isComputed() {
        return computed;
    }

    public boolean isShorthand() {
        return shorthand;
    }

    public String getKind() {
        return kind;
    }
}
