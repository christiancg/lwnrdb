package org.techhouse.ops.filter;

final class SubstringNeedle {
    private final String needle;
    private final int[] prefix;

    private SubstringNeedle(String needle) {
        this.needle = needle;
        this.prefix = failureFunction(needle);
    }

    static SubstringNeedle of(String needle) {
        return new SubstringNeedle(needle);
    }

    boolean foundIn(String haystack) {
        final var needleLength = needle.length();
        if (needleLength == 0) {
            return true;
        }
        if (needleLength > haystack.length()) {
            return false;
        }
        var matched = 0;
        for (var i = 0; i < haystack.length(); i++) {
            while (matched > 0 && haystack.charAt(i) != needle.charAt(matched)) {
                matched = prefix[matched - 1];
            }
            if (haystack.charAt(i) == needle.charAt(matched)) {
                matched++;
            }
            if (matched == needleLength) {
                return true;
            }
        }
        return false;
    }

    private static int[] failureFunction(String needle) {
        final var table = new int[needle.length()];
        var length = 0;
        for (var i = 1; i < needle.length(); i++) {
            while (length > 0 && needle.charAt(i) != needle.charAt(length)) {
                length = table[length - 1];
            }
            if (needle.charAt(i) == needle.charAt(length)) {
                length++;
            }
            table[i] = length;
        }
        return table;
    }
}
