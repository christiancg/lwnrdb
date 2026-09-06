package org.techhouse.simplejs.values;

import java.math.BigInteger;

public final class JsBigInt extends JsValue {
    private final BigInteger value;

    public JsBigInt(BigInteger value) {
        this.value = value;
    }

    public BigInteger getValue() {
        return value;
    }
}
