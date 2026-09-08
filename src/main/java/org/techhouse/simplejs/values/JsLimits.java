package org.techhouse.simplejs.values;

import java.math.BigInteger;

public final class JsLimits {
    public static final double MAX_SAFE_INTEGER = 9007199254740991d;
    public static final long MAX_SAFE_INTEGER_LONG = 9007199254740991L;
    public static final BigInteger MAX_SAFE_INTEGER_BIG = BigInteger.valueOf(MAX_SAFE_INTEGER_LONG);

    public static final long MAX_ARRAY_LENGTH = 4_294_967_295L;
    public static final double MAX_ARRAY_LENGTH_DOUBLE = MAX_ARRAY_LENGTH;
    public static final long MAX_ARRAY_INDEX = MAX_ARRAY_LENGTH - 1;

    public static final double MAX_LIST_LENGTH = Integer.MAX_VALUE;

    private JsLimits() {
    }
}
