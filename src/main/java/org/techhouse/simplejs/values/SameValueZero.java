package org.techhouse.simplejs.values;

/**
 * Normalizes a {@link JsValue} into a key usable in a Java hash map so that {@code Map}/{@code Set}
 * membership follows the SameValueZero algorithm: primitives compare by value (with {@code +0} and
 * {@code -0} unified and {@code NaN} equal to itself), everything else by identity.
 */
public final class SameValueZero {
    private static final Object NULL_KEY = new Object();
    private static final Object UNDEFINED_KEY = new Object();

    private SameValueZero() {
    }

    public static Object key(JsValue value) {
        return switch (value) {
            case JsNumber n -> n.getValue() == 0 ? Double.valueOf(0) : Double.valueOf(n.getValue());
            case JsString s -> s.getValue();
            case JsBoolean b -> b.getValue();
            case JsBigInt b -> b.getValue();
            case JsNull ignored -> NULL_KEY;
            case JsUndefined ignored -> UNDEFINED_KEY;
            default -> value;
        };
    }

    public static boolean equal(JsValue a, JsValue b) {
        return key(a).equals(key(b));
    }
}
