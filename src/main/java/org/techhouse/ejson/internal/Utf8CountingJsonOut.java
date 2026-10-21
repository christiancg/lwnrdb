package org.techhouse.ejson.internal;

public final class Utf8CountingJsonOut implements JsonOut {
    private static final int LONG_MIN_VALUE_CHARS = 20;
    private static final int DECIMAL_RADIX = 10;

    private long bytes;
    private boolean pendingHighSurrogate;

    public long length() {
        return pendingHighSurrogate ? bytes + 3 : bytes;
    }

    @Override
    public JsonOut append(char c) {
        count(c);
        return this;
    }

    @Override
    public JsonOut append(String text, int start, int end) {
        for (var i = start; i < end; i++) {
            count(text.charAt(i));
        }
        return this;
    }

    @Override
    public void appendLong(long value) {
        settlePendingHighSurrogate();
        bytes += decimalChars(value);
    }

    @Override
    public int mark() {
        return (int) length();
    }

    @Override
    public void reset(int mark) {
        bytes = mark;
        pendingHighSurrogate = false;
    }

    private void count(char c) {
        if (pendingHighSurrogate) {
            pendingHighSurrogate = false;
            if (Character.isLowSurrogate(c)) {
                bytes += 4;
                return;
            }
            bytes += 3;
        }
        if (c < 0x80) {
            bytes += 1;
        } else if (c < 0x800) {
            bytes += 2;
        } else if (Character.isHighSurrogate(c)) {
            pendingHighSurrogate = true;
        } else {
            bytes += 3;
        }
    }

    private void settlePendingHighSurrogate() {
        if (pendingHighSurrogate) {
            pendingHighSurrogate = false;
            bytes += 3;
        }
    }

    private static int decimalChars(long value) {
        if (value == Long.MIN_VALUE) {
            return LONG_MIN_VALUE_CHARS;
        }
        var chars = value < 0 ? 1 : 0;
        var remaining = Math.abs(value);
        do {
            chars++;
            remaining /= DECIMAL_RADIX;
        } while (remaining > 0);
        return chars;
    }
}
