package org.techhouse.simplejs.nodes;

public class NumberLiteral extends Expression {
    private final Number value;
    private org.techhouse.simplejs.values.JsNumber boxed;

    public NumberLiteral(Number value) {
        this.value = value;
    }

    public Number getValue() {
        return value;
    }

    public org.techhouse.simplejs.values.JsNumber boxed() {
        var cached = boxed;
        if (cached == null) {
            cached = new org.techhouse.simplejs.values.JsNumber(value.doubleValue());
            boxed = cached;
        }
        return cached;
    }

    @Override
    public NodeType getType() {
        return NodeType.NUMBER_LITERAL;
    }
}
