package org.techhouse.simplejs.nodes;

public class MemberExpression extends Expression {
    private final Expression object;
    private final Expression property;
    private final boolean computed;
    private final boolean optional;
    private org.techhouse.simplejs.values.JsString keyValue;

    public org.techhouse.simplejs.values.JsString staticKeyValue() {
        var cached = keyValue;
        if (cached == null) {
            if (computed || !(property instanceof Identifier id)) {
                return null;
            }
            cached = new org.techhouse.simplejs.values.JsString(id.getName());
            keyValue = cached;
        }
        return cached;
    }

    public MemberExpression(Expression object, Expression property, boolean computed, boolean optional) {
        this.object = object;
        this.property = property;
        this.computed = computed;
        this.optional = optional;
    }

    public Expression getObject() {
        return object;
    }

    public Expression getProperty() {
        return property;
    }

    public boolean isComputed() {
        return computed;
    }

    public boolean isOptional() {
        return optional;
    }

    @Override
    public NodeType getType() {
        return NodeType.MEMBER_EXPRESSION;
    }
}
