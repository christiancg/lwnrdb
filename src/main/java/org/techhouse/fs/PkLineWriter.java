package org.techhouse.fs;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;

final class PkLineWriter {
    private static final byte[] SEPARATOR = Globals.ID_SEPARATOR.getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEWLINE = Globals.NEWLINE.getBytes(StandardCharsets.UTF_8);
    private static final int MAX_LONG_DIGITS = 20;
    private static final char LAST_ASCII = 0x7F;
    private static final int INITIAL_LINE_BYTES = 128;

    private final OutputStream out;
    private byte[] line = new byte[INITIAL_LINE_BYTES];
    private int size;

    PkLineWriter(OutputStream out) {
        this.out = out;
    }

    long write(PkIndexEntry row) throws IOException {
        return write(row, row.getPosition());
    }

    long write(PkIndexEntry row, long position) throws IOException {
        size = 0;
        appendValue(FieldIndexEntry.escapeIndexToken(row.getValue()));
        appendField(position);
        appendField(row.getLength());
        appendField(row.getPage());
        appendField(row.getVersion());
        append(NEWLINE);
        out.write(line, 0, size);
        return PkRowDigest.of(row, position);
    }

    private void appendValue(String value) {
        for (var i = 0; i < value.length(); i++) {
            if (value.charAt(i) > LAST_ASCII) {
                append(value.getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        ensure(value.length());
        for (var i = 0; i < value.length(); i++) {
            line[size++] = (byte) value.charAt(i);
        }
    }

    private void appendField(long value) {
        append(SEPARATOR);
        ensure(MAX_LONG_DIGITS);
        var remaining = value;
        if (remaining < 0) {
            line[size++] = '-';
        } else {
            remaining = -remaining;
        }
        final var start = size;
        do {
            line[size++] = (byte) ('0' - remaining % 10);
            remaining /= 10;
        } while (remaining != 0);
        for (int left = start, right = size - 1; left < right; left++, right--) {
            final var digit = line[left];
            line[left] = line[right];
            line[right] = digit;
        }
    }

    private void append(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, line, size, bytes.length);
        size += bytes.length;
    }

    private void ensure(int extra) {
        if (size + extra > line.length) {
            line = Arrays.copyOf(line, Math.max(line.length * 2, size + extra));
        }
    }
}
