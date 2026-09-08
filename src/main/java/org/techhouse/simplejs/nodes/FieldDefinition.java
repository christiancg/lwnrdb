package org.techhouse.simplejs.nodes;

public class FieldDefinition extends JsNode {
    private final Expression key;
    private final Expression value;
    private final boolean isStatic;
    private final boolean computed;

    public FieldDefinition(Expression key, Expression value, boolean isStatic, boolean computed) {
        this.key = key;
        this.value = value;
        this.isStatic = isStatic;
        this.computed = computed;
    }

    public Expression getKey() {
        return key;
    }

    public Expression getValue() {
        return value;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public boolean isComputed() {
        return computed;
    }
}
