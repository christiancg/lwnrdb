package org.techhouse.ejson.internal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public final class NumberFormatter {
    private NumberFormatter() {
    }

    private static final double TWO_POW_32 = 4294967296d;
    private static final double TWO_POW_53 = 9007199254740992d;
    private static final int MAX_PLAIN_EXPONENT = 21;
    private static final int MIN_PLAIN_EXPONENT = -6;
    private static final int MAX_CHARS = 26;
    private static final int MAX_DIGITS = 17;
    private static final long[] POWERS_OF_TEN = new long[MAX_DIGITS];

    static {
        POWERS_OF_TEN[0] = 1;
        for (var i = 1; i < MAX_DIGITS; i++) {
            POWERS_OF_TEN[i] = POWERS_OF_TEN[i - 1] * 10;
        }
    }

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
        final var out = new CharJsonOut(new StringBuilder(MAX_CHARS));
        appendFinite(value, out);
        return out.toString();
    }

    public static void appendJs(final double value, final JsonOut out) {
        if (Math.abs(value) < TWO_POW_53 && value == Math.floor(value)) {
            out.appendLong((long) value);
        } else if (Double.isNaN(value) || Double.isInfinite(value)) {
            out.append(toJsString(value));
        } else {
            appendFinite(value, out);
        }
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

    private static void appendFinite(final double value, final JsonOut out) {
        if (value < 0) {
            out.append('-');
        }
        final var magnitude = Math.abs(value);
        if (magnitude < TWO_POW_53 && magnitude == Math.floor(magnitude)) {
            out.appendLong((long) magnitude);
        } else if (magnitude < Double.MIN_NORMAL) {
            appendSubnormal(magnitude, out);
        } else {
            appendNormal(magnitude, out);
        }
    }

    private static void appendNormal(final double magnitude, final JsonOut out) {
        final var text = Double.toString(magnitude);
        long digits = 0;
        var count = 0;
        var integerDigits = 0;
        var leadingZeros = 0;
        var exponent = 0;
        for (var i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (c == 'E') {
                exponent = Integer.parseInt(text, i + 1, text.length(), 10);
                break;
            }
            if (c == '.') {
                integerDigits = i;
            } else if (digits == 0 && c == '0') {
                leadingZeros++;
            } else {
                digits = digits * 10 + (c - '0');
                count++;
            }
        }
        while (digits % 10 == 0) {
            digits /= 10;
            count--;
        }
        appendDecimal(digits, count, integerDigits - leadingZeros + exponent, out);
    }

    private static void appendSubnormal(final double magnitude, final JsonOut out) {
        final var decimal = shortestDecimal(magnitude);
        final var digits = decimal.unscaledValue().longValueExact();
        var count = 1;
        while (count < MAX_DIGITS && digits >= POWERS_OF_TEN[count]) {
            count++;
        }
        appendDecimal(digits, count, count - decimal.scale(), out);
    }

    private static void appendDecimal(final long digits, final int count, final int point, final JsonOut out) {
        if (count <= point && point <= MAX_PLAIN_EXPONENT) {
            appendDigits(digits, count, 0, count, out);
            appendZeros(point - count, out);
        } else if (0 < point && point <= MAX_PLAIN_EXPONENT) {
            appendDigits(digits, count, 0, point, out);
            out.append('.');
            appendDigits(digits, count, point, count, out);
        } else if (MIN_PLAIN_EXPONENT < point && point <= 0) {
            out.append('0').append('.');
            appendZeros(-point, out);
            appendDigits(digits, count, 0, count, out);
        } else {
            appendDigits(digits, count, 0, 1, out);
            if (count > 1) {
                out.append('.');
                appendDigits(digits, count, 1, count, out);
            }
            final var exponent = point - 1;
            out.append('e').append(exponent < 0 ? '-' : '+').appendLong(Math.abs(exponent));
        }
    }

    private static void appendDigits(final long digits, final int count, final int from, final int to,
            final JsonOut out) {
        for (var i = from; i < to; i++) {
            out.append((char) ('0' + digits / POWERS_OF_TEN[count - 1 - i] % 10));
        }
    }

    private static void appendZeros(final int zeros, final JsonOut out) {
        for (var i = 0; i < zeros; i++) {
            out.append('0');
        }
    }

    // Double.toString is shortest-round-trip for normal values but not for subnormals (Double.MIN_VALUE
    // renders as 4.9E-324 where the spec's shortest digit string is 5), so only those search.
    private static BigDecimal shortestDecimal(final double magnitude) {
        final var exact = new BigDecimal(Double.toString(magnitude)).stripTrailingZeros();
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
