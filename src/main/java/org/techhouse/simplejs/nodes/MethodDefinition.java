package org.techhouse.simplejs.nodes;

public class MethodDefinition extends JsNode {
    private final Expression key;
    private final FunctionExpression value;
    private final String kind;
    private final boolean isStatic;
    private final boolean computed;

    public MethodDefinition(Expression key, FunctionExpression value, String kind, boolean isStatic, boolean computed) {
        this.key = key;
        this.value = value;
        this.kind = kind;
        this.isStatic = isStatic;
        this.computed = computed;
    }

    public Expression getKey() {
        return key;
    }

    public FunctionExpression getValue() {
        return value;
    }

    public String getKind() {
        return kind;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public boolean isComputed() {
        return computed;
    }
}
