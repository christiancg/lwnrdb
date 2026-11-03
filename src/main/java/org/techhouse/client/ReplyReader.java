package org.techhouse.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class ReplyReader {
    private static final int BUFFER_BYTES = 64 * 1024;

    private final InputStream in;
    private final byte[] buffer = new byte[BUFFER_BYTES];
    private int position;
    private int limit;

    ReplyReader(InputStream in) {
        this.in = in;
    }

    String readLine() throws IOException {
        final var line = new ByteArrayOutputStream();
        if (endsBeforeNextLine(line, null)) {
            return null;
        }
        final var bytes = line.toByteArray();
        final var length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    boolean endsBeforeNextLine(OutputStream sink, ReplyHeader header) throws IOException {
        var any = false;
        while (true) {
            if (position == limit && !fill()) {
                return !any;
            }
            any = true;
            var end = position;
            while (end < limit && buffer[end] != '\n') {
                end++;
            }
            sink.write(buffer, position, end - position);
            if (header != null) {
                header.accept(buffer, position, end);
            }
            if (end < limit) {
                position = end + 1;
                return false;
            }
            position = limit;
        }
    }

    private boolean fill() throws IOException {
        final var read = in.read(buffer);
        if (read <= 0) {
            return false;
        }
        position = 0;
        limit = read;
        return true;
    }
}
