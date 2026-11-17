package io.github.christiancg.lwnrdb.client.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

final class LineReader {
    private static final int CHUNK = 64 * 1024;
    private static final int INITIAL_LINE = 8 * 1024;

    private final InputStream in;
    private final long maxLineBytes;
    private final byte[] chunk = new byte[CHUNK];
    private int chunkStart;
    private int chunkEnd;

    record Line(byte[] bytes, int length, boolean tooLarge) {
    }

    LineReader(InputStream in, long maxLineBytes) {
        this.in = in;
        this.maxLineBytes = maxLineBytes;
    }

    Line next() throws IOException {
        var line = new byte[INITIAL_LINE];
        var length = 0;
        var tooLarge = false;
        while (true) {
            if (chunkStart == chunkEnd && !fill()) {
                return null;
            }
            var newline = chunkStart;
            while (newline < chunkEnd && chunk[newline] != '\n') {
                newline++;
            }
            final var segment = newline - chunkStart;
            if (!tooLarge && length + (long) segment > maxLineBytes) {
                tooLarge = true;
                line = new byte[0];
                length = 0;
            }
            if (!tooLarge) {
                if (length + segment > line.length) {
                    line = Arrays.copyOf(line, Math.max(line.length * 2, length + segment));
                }
                System.arraycopy(chunk, chunkStart, line, length, segment);
                length += segment;
            }
            chunkStart = newline;
            if (newline < chunkEnd) {
                chunkStart++;
                if (length > 0 && line[length - 1] == '\r') {
                    length--;
                }
                return new Line(line, length, tooLarge);
            }
        }
    }

    private boolean fill() throws IOException {
        final var read = in.read(chunk);
        if (read < 0) {
            return false;
        }
        chunkStart = 0;
        chunkEnd = read;
        return true;
    }
}
