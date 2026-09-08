package org.techhouse.simplejs.builtins.string;

import static org.techhouse.simplejs.builtins.BuiltinArgs.str;

import java.util.List;
import java.util.Locale;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.LocaleResolver;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class StringUnicode {
    private static final int GREEK_CAPITAL_SIGMA = 0x03A3;
    private static final int GREEK_SMALL_SIGMA = 0x03C3;
    private static final int GREEK_SMALL_FINAL_SIGMA = 0x03C2;

    public static boolean isWellFormed(String value) {
        for (var i = 0; i < value.length(); i++) {
            final var ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return false;
            }
        }
        return true;
    }

    public static String toWellFormed(String value) {
        final var result = new StringBuilder(value.length());
        for (var i = 0; i < value.length(); i++) {
            final var ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    result.append(ch).append(value.charAt(i + 1));
                    i++;
                } else {
                    result.append('�');
                }
            } else if (Character.isLowSurrogate(ch)) {
                result.append('�');
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    public static String toLowerCaseWithFinalSigma(String value, Locale locale) {
        if (value.indexOf(GREEK_CAPITAL_SIGMA) < 0) {
            return value.toLowerCase(locale);
        }
        final var codePoints = value.codePoints().toArray();
        final var result = new StringBuilder(value.length());
        for (var i = 0; i < codePoints.length; i++) {
            final var codePoint = codePoints[i];
            if (codePoint == GREEK_CAPITAL_SIGMA) {
                result.appendCodePoint(
                        isFinalSigmaContext(codePoints, i) ? GREEK_SMALL_FINAL_SIGMA : GREEK_SMALL_SIGMA);
            } else {
                result.append(new String(Character.toChars(codePoint)).toLowerCase(locale));
            }
        }
        return result.toString();
    }

    public static boolean isFinalSigmaContext(int[] codePoints, int index) {
        final var before = firstNonIgnorableBefore(codePoints, index);
        if (before < 0 || isNotCased(codePoints[before])) {
            return false;
        }
        final var after = firstNonIgnorableAfter(codePoints, index);
        return after < 0 || isNotCased(codePoints[after]);
    }

    public static int firstNonIgnorableBefore(int[] codePoints, int index) {
        var i = index - 1;
        while (i >= 0 && isCaseIgnorable(codePoints[i])) {
            i--;
        }
        return i;
    }

    public static int firstNonIgnorableAfter(int[] codePoints, int index) {
        var i = index + 1;
        while (i < codePoints.length && isCaseIgnorable(codePoints[i])) {
            i++;
        }
        return i < codePoints.length ? i : -1;
    }

    public static boolean isNotCased(int codePoint) {
        return !Character.isUpperCase(codePoint) && !Character.isLowerCase(codePoint)
                && Character.getType(codePoint) != Character.TITLECASE_LETTER;
    }

    public static boolean isCaseIgnorable(int codePoint) {
        final var type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.FORMAT
                || type == Character.MODIFIER_LETTER || type == Character.MODIFIER_SYMBOL) {
            return true;
        }
        return switch (codePoint) {
            case 0x0027, 0x002E, 0x003A, 0x00B7, 0x0387, 0x055F, 0x05F4, 0x2018, 0x2019, 0x2024, 0x2027, 0xFE13, 0xFE52,
                    0xFE55, 0xFF07, 0xFF0E, 0xFF1A ->
                true;
            default -> false;
        };
    }

    public static String normalize(String value, List<JsValue> args, InterpreterOps ops) {
        final var form = args.isEmpty() || args.getFirst() instanceof JsUndefined ? "NFC" : str(args, 0, ops);
        try {
            return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.valueOf(form));
        } catch (IllegalArgumentException invalidForm) {
            throw new org.techhouse.simplejs.exceptions.RangeErrorException(
                    "The normalization form should be one of NFC, NFD, NFKC, NFKD.");
        }
    }

    public static int localeCompare(String value, List<JsValue> args, InterpreterOps ops) {
        final var that = str(args, 0, ops);
        final var collator = java.text.Collator.getInstance(LocaleResolver.resolve(args, 1, ops));
        collator.setDecomposition(java.text.Collator.CANONICAL_DECOMPOSITION);
        final var sensitivity = LocaleResolver.sensitivity(args, 2, ops);
        if (sensitivity != null) {
            collator.setStrength(strengthFor(sensitivity));
        }
        return Integer.signum(collator.compare(value, that));
    }

    public static int strengthFor(String sensitivity) {
        return switch (sensitivity) {
            case "base" -> java.text.Collator.PRIMARY;
            case "accent" -> java.text.Collator.SECONDARY;
            default -> java.text.Collator.TERTIARY;
        };
    }

    private StringUnicode() {
    }
}
