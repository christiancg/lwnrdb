package org.techhouse.simplejs.nodes;

import java.math.BigInteger;

public class BigIntLiteral extends Expression {
    private final BigInteger value;

    public BigIntLiteral(BigInteger value) {
        this.value = value;
    }

    public BigInteger getValue() {
        return value;
    }
}
