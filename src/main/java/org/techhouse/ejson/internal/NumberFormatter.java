package org.techhouse.ejson.internal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public final class NumberFormatter {
    private NumberFormatter() {
    }

    private static final double TWO_POW_32 = 4294967296d;
    private static final int MAX_PLAIN_EXPONENT = 21;
    private static final int MIN_PLAIN_EXPONENT = -6;

    public static String toJsString(final double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == 0) {
            return "0";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        final var magnitude = format(Math.abs(value));
        return value < 0 ? "-" + magnitude : magnitude;
    }

    public static int toInt32(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        final var truncated = Math.signum(value) * Math.floor(Math.abs(value));
        var wrapped = truncated % TWO_POW_32;
        if (wrapped < 0) {
            wrapped += TWO_POW_32;
        }
        return (int) (long) wrapped;
    }

    public static long toUint32(final double value) {
        return toInt32(value) & 0xffffffffL;
    }

    private static String format(final double magnitude) {
        final var decimal = shortestDecimal(magnitude);
        final var digits = decimal.unscaledValue().toString();
        final var k = digits.length();
        final var n = k - decimal.scale();
        if (k <= n && n <= MAX_PLAIN_EXPONENT) {
            return digits + "0".repeat(n - k);
        }
        if (0 < n && n <= MAX_PLAIN_EXPONENT) {
            return digits.substring(0, n) + '.' + digits.substring(n);
        }
        if (MIN_PLAIN_EXPONENT < n && n <= 0) {
            return "0." + "0".repeat(-n) + digits;
        }
        final var mantissa = k == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        final var exponent = n - 1;
        return mantissa + 'e' + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
    }

    /*
     * Double.toString is shortest-round-trip for normal values, but not for subnormals (it renders
     * Double.MIN_VALUE as 4.9E-324 where the spec's shortest digit string is 5). Only those pay for
     * the round-trip search.
     */
    private static BigDecimal shortestDecimal(final double magnitude) {
        final var exact = new BigDecimal(Double.toString(magnitude)).stripTrailingZeros();
        if (magnitude >= Double.MIN_NORMAL) {
            return exact;
        }
        final var length = exact.unscaledValue().toString().length();
        for (var precision = 1; precision < length; precision++) {
            final var candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN)).stripTrailingZeros();
            if (Double.parseDouble(candidate.toString()) == magnitude) {
                return candidate;
            }
        }
        return exact;
    }
}
