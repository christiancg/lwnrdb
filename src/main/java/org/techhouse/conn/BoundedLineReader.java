package org.techhouse.conn;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.techhouse.config.Globals;
import org.techhouse.ex.RequestTooLargeException;

public final class BoundedLineReader {
    @FunctionalInterface
    public interface ReadTimeout {
        void set(int millis) throws IOException;
    }

    private static final byte LINE_FEED = '\n';
    private static final byte CARRIAGE_RETURN = '\r';
    private static final int UNSET_TIMEOUT = -1;
    private static final int SHRINK_ABOVE_BYTES = 1 << 20;

    private final InputStream in;
    private final ReadTimeout readTimeout;
    private byte[] buffer = new byte[Globals.BUFFER_SIZE];
    private int start;
    private int end;
    private int scanned;
    private boolean eof;
    private boolean refused;
    private int currentTimeout = UNSET_TIMEOUT;

    public BoundedLineReader(InputStream in, ReadTimeout readTimeout) {
        this.in = in;
        this.readTimeout = readTimeout;
    }

    public String readLine(int maxLineBytes) throws IOException {
        return readLine(0, maxLineBytes);
    }

    public String readLine(long budgetMillis, int maxLineBytes) throws IOException {
        final var line = readLineBytes(budgetMillis, maxLineBytes);
        return line == null ? null : line.text();
    }

    public RequestLine readLineBytes(long budgetMillis, int maxLineBytes) throws IOException {
        if (refused) {
            throw new RequestTooLargeException(maxLineBytes);
        }
        final var timed = budgetMillis > 0;
        final var deadline = timed ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis) : 0L;
        while (true) {
            final var newline = findNewline();
            if (newline >= 0) {
                return takeLine(newline, newline + 1, maxLineBytes);
            }
            if (end - start > maxLineBytes + 1L) {
                throw refuse(maxLineBytes);
            }
            if (eof) {
                return end > start ? takeLine(end, end, maxLineBytes) : null;
            }
            armTimeout(timed, deadline);
            fill(maxLineBytes);
        }
    }

    private int findNewline() {
        for (var i = start + scanned; i < end; i++) {
            if (buffer[i] == LINE_FEED) {
                return i;
            }
        }
        scanned = end - start;
        return -1;
    }

    private RequestLine takeLine(int contentEnd, int next, int maxLineBytes) throws RequestTooLargeException {
        var length = contentEnd - start;
        if (length > 0 && buffer[contentEnd - 1] == CARRIAGE_RETURN) {
            length--;
        }
        if (length > maxLineBytes) {
            throw refuse(maxLineBytes);
        }
        final var line = new RequestLine(buffer, start, start + length);
        start = next;
        scanned = 0;
        if (start == end) {
            start = 0;
            end = 0;
        }
        dropOversizedBuffer();
        return line;
    }

    private void dropOversizedBuffer() {
        final var leftover = end - start;
        if (buffer.length <= SHRINK_ABOVE_BYTES || leftover > Globals.BUFFER_SIZE) {
            return;
        }
        final var fresh = new byte[Globals.BUFFER_SIZE];
        System.arraycopy(buffer, start, fresh, 0, leftover);
        buffer = fresh;
        start = 0;
        end = leftover;
    }

    private RequestTooLargeException refuse(int maxLineBytes) {
        refused = true;
        buffer = new byte[0];
        start = 0;
        end = 0;
        scanned = 0;
        return new RequestTooLargeException(maxLineBytes);
    }

    private void armTimeout(boolean timed, long deadline) throws IOException {
        var millis = 0;
        if (timed) {
            final var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new SocketTimeoutException("The read deadline expired");
            }
            millis = (int) Math.clamp(TimeUnit.NANOSECONDS.toMillis(remaining), 1L, Integer.MAX_VALUE);
        }
        if (millis != currentTimeout) {
            readTimeout.set(millis);
            currentTimeout = millis;
        }
    }

    private void fill(int maxLineBytes) throws IOException {
        if (end == buffer.length) {
            makeRoom(maxLineBytes);
        }
        final var room = (int) Math.min(buffer.length - end, maxLineBytes + 2L - (end - start));
        final var read = in.read(buffer, end, room);
        if (read < 0) {
            eof = true;
        } else {
            end += read;
        }
    }

    private void makeRoom(int maxLineBytes) {
        if (start > 0) {
            System.arraycopy(buffer, start, buffer, 0, end - start);
            end -= start;
            start = 0;
            return;
        }
        buffer = Arrays.copyOf(buffer, (int) Math.min(buffer.length * 2L, maxLineBytes + 2L));
    }
}
