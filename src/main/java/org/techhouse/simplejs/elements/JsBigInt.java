package org.techhouse.simplejs.elements;

import java.math.BigInteger;

public class JsBigInt extends JsBaseElement {
    private final BigInteger value;
    public JsBigInt(BigInteger value) {
        this.value = value;
    }
    public BigInteger getValue() {
        return value;
    }
}
