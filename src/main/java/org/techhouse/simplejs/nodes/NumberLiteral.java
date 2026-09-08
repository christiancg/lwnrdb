package org.techhouse.simplejs.nodes;

public class NumberLiteral extends Expression {
    private final Number value;

    public NumberLiteral(Number value) {
        this.value = value;
    }

    public Number getValue() {
        return value;
    }
}
