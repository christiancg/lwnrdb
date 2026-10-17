package org.techhouse.simplejs.builtins.string;

import org.techhouse.simplejs.builtins.InterpreterOps;

public final class StringSearch {
    private static final long DIRECT_SEARCH_WORK = 1L << 24;
    private static final int PACE_INTERVAL_MASK = 0x3FF;

    private StringSearch() {
    }

    public static int indexOf(String haystack, String needle, int from, InterpreterOps ops) {
        final var start = Math.max(0, from);
        if (isDirect(haystack.length() - (long) start, needle.length())) {
            return haystack.indexOf(needle, start);
        }
        final var failure = failureTable(needle, false, ops);
        try {
            var matched = 0;
            for (var i = start; i < haystack.length(); i++) {
                pace(ops, i - start);
                matched = advance(needle, false, failure, matched, haystack.charAt(i));
                if (matched == needle.length()) {
                    return i - matched + 1;
                }
            }
            return -1;
        } finally {
            InterpreterOps.release(ops, tableBytes(needle));
        }
    }

    public static int lastIndexOf(String haystack, String needle, int from, InterpreterOps ops) {
        final var lastStart = Math.min(from, haystack.length() - needle.length());
        if (lastStart < 0 || isDirect(lastStart + 1L, needle.length())) {
            return haystack.lastIndexOf(needle, from);
        }
        final var failure = failureTable(needle, true, ops);
        try {
            var matched = 0;
            for (var i = lastStart + needle.length() - 1; i >= 0; i--) {
                pace(ops, i);
                matched = advance(needle, true, failure, matched, haystack.charAt(i));
                if (matched == needle.length()) {
                    return i;
                }
            }
            return -1;
        } finally {
            InterpreterOps.release(ops, tableBytes(needle));
        }
    }

    private static boolean isDirect(long positions, int needleLength) {
        return needleLength <= 1 || positions * needleLength <= DIRECT_SEARCH_WORK;
    }

    private static int advance(String needle, boolean reversed, int[] failure, int matched, char c) {
        var next = matched;
        while (next > 0 && charAt(needle, next, reversed) != c) {
            next = failure[next - 1];
        }
        return charAt(needle, next, reversed) == c ? next + 1 : next;
    }

    private static int[] failureTable(String needle, boolean reversed, InterpreterOps ops) {
        InterpreterOps.charge(ops, tableBytes(needle));
        final var failure = new int[needle.length()];
        var matched = 0;
        for (var i = 1; i < needle.length(); i++) {
            pace(ops, i);
            matched = advance(needle, reversed, failure, matched, charAt(needle, i, reversed));
            failure[i] = matched;
        }
        return failure;
    }

    private static long tableBytes(String needle) {
        return (long) needle.length() * Integer.BYTES;
    }

    private static char charAt(String value, int index, boolean reversed) {
        return reversed ? value.charAt(value.length() - 1 - index) : value.charAt(index);
    }

    private static void pace(InterpreterOps ops, long step) {
        if ((step & PACE_INTERVAL_MASK) == 0) {
            InterpreterOps.tick(ops);
        }
    }
}
