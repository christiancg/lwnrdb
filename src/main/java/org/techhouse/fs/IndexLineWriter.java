package org.techhouse.fs;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;

final class IndexLineWriter {
    private static final byte[] SEPARATOR = Globals.ID_SEPARATOR.getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEWLINE = Globals.NEWLINE.getBytes(StandardCharsets.UTF_8);
    private static final char LAST_ASCII = 0x7F;
    private static final int INITIAL_LINE_BYTES = 256;

    private final OutputStream out;
    private byte[] line = new byte[INITIAL_LINE_BYTES];
    private int size;

    IndexLineWriter(OutputStream out) {
        this.out = out;
    }

    void write(FieldIndexEntry<?> entry) throws IOException {
        size = 0;
        appendToken(FieldIndexEntry.fileKeyOf(entry.getValue()));
        for (final var id : entry.getIds()) {
            append(SEPARATOR);
            appendToken(FieldIndexEntry.escapeIndexToken(id));
        }
        append(NEWLINE);
        out.write(line, 0, size);
    }

    private void appendToken(String token) {
        for (var i = 0; i < token.length(); i++) {
            if (token.charAt(i) > LAST_ASCII) {
                append(token.getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        ensure(token.length());
        for (var i = 0; i < token.length(); i++) {
            line[size++] = (byte) token.charAt(i);
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
