package org.techhouse.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class ReplyReader {
    private static final int BUFFER_BYTES = 64 * 1024;

    private final InputStream in;
    private final int maxLineBytes;
    private final byte[] buffer = new byte[BUFFER_BYTES];
    private int position;
    private int limit;

    ReplyReader(InputStream in, int maxLineBytes) {
        this.in = in;
        this.maxLineBytes = maxLineBytes;
    }

    static final class ReplyTooLongException extends IOException {
        ReplyTooLongException(int maxLineBytes) {
            super("the server's reply is longer than " + maxLineBytes + " bytes");
        }
    }

    private static final class BoundedLine extends ByteArrayOutputStream {
        private final int maxBytes;

        BoundedLine(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public synchronized void write(byte[] bytes, int offset, int length) {
            if ((long) count + length > maxBytes + 1L) {
                throw new UncheckedIOException(new ReplyTooLongException(maxBytes));
            }
            super.write(bytes, offset, length);
        }

        synchronized String text() throws ReplyTooLongException {
            final var length = count > 0 && buf[count - 1] == '\r' ? count - 1 : count;
            if (length > maxBytes) {
                throw new ReplyTooLongException(maxBytes);
            }
            return new String(buf, 0, length, StandardCharsets.UTF_8);
        }
    }

    String readLine() throws IOException {
        final var line = new BoundedLine(maxLineBytes);
        final boolean ended;
        try {
            ended = endsBeforeNextLine(line, null);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        if (ended) {
            return null;
        }
        return line.text();
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
