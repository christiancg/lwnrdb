package org.techhouse.utils;

public final class CaseFolding {
    private CaseFolding() {
    }

    public static int compare(String left, String right) {
        var i = 0;
        var j = 0;
        while (i < left.length() && j < right.length()) {
            final var leftCodePoint = left.codePointAt(i);
            final var rightCodePoint = right.codePointAt(j);
            final var diff = Integer.compare(fold(leftCodePoint), fold(rightCodePoint));
            if (diff != 0) {
                return diff;
            }
            i += Character.charCount(leftCodePoint);
            j += Character.charCount(rightCodePoint);
        }
        return Boolean.compare(i < left.length(), j < right.length());
    }

    public static boolean equal(String left, String right) {
        return compare(left, right) == 0;
    }

    public static String foldKey(String value) {
        final var builder = new StringBuilder(value.length());
        var i = 0;
        while (i < value.length()) {
            final var codePoint = value.codePointAt(i);
            builder.appendCodePoint(fold(codePoint));
            i += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    public static int foldHash(String value) {
        var hash = 0;
        var i = 0;
        while (i < value.length()) {
            final var codePoint = value.codePointAt(i);
            hash = 31 * hash + fold(codePoint);
            i += Character.charCount(codePoint);
        }
        return hash;
    }

    private static int fold(int codePoint) {
        return Character.toLowerCase(Character.toUpperCase(codePoint));
    }
}
