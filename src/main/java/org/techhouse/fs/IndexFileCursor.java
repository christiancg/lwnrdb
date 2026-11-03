package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.techhouse.config.Globals;

final class IndexFileCursor implements AutoCloseable {
    static final byte SEPARATOR = (byte) Globals.ID_SEPARATOR.charAt(0);
    private static final int BLOCK = 4096;
    private static final byte LINE_FEED = '\n';
    private static final byte CARRIAGE_RETURN = '\r';

    private final FileChannel channel;
    private final long length;
    private final byte[] block = new byte[BLOCK];
    private long blockStart = -1;
    private int blockLength;
    private byte[] buffer = new byte[BLOCK];
    private long readPosition;
    private int lineStart;
    private int currentStart;
    private int lineEnd;
    private int scanned;
    private int filled;
    private boolean exhausted;
    private boolean terminated;

    private IndexFileCursor(FileChannel channel) throws IOException {
        this.channel = channel;
        this.length = channel.size();
    }

    static IndexFileCursor open(File file) throws IOException {
        try {
            return new IndexFileCursor(FileChannel.open(file.toPath(), StandardOpenOption.READ));
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    long length() {
        return length;
    }

    private static boolean isTerminator(int value) {
        return value == LINE_FEED || value == CARRIAGE_RETURN;
    }

    private int byteAt(long position) throws IOException {
        if (position < 0 || position >= length) {
            return -1;
        }
        if (blockStart < 0 || position < blockStart || position >= blockStart + blockLength) {
            blockStart = position - position % BLOCK;
            final var target = ByteBuffer.wrap(block);
            while (target.hasRemaining() && blockStart + target.position() < length) {
                if (channel.read(target, blockStart + target.position()) < 0) {
                    break;
                }
            }
            blockLength = target.position();
            if (position >= blockStart + blockLength) {
                return -1;
            }
        }
        return block[(int) (position - blockStart)] & 0xFF;
    }

    long lineStartAtOrAfter(long position) throws IOException {
        var start = 0L;
        if (position > 0) {
            var at = position - 1;
            while (at < length && !isTerminator(byteAt(at))) {
                at++;
            }
            start = at + 1;
        }
        while (start < length && isTerminator(byteAt(start))) {
            start++;
        }
        return Math.min(start, length);
    }

    long previousLineStart(long lineStart) throws IOException {
        var at = lineStart - 1;
        while (at >= 0 && isTerminator(byteAt(at))) {
            at--;
        }
        if (at < 0) {
            return -1;
        }
        while (at > 0 && !isTerminator(byteAt(at - 1))) {
            at--;
        }
        return at;
    }

    byte[] keyAt(long lineStart) throws IOException {
        var key = new byte[64];
        var size = 0;
        for (var at = lineStart;; at++) {
            final var value = byteAt(at);
            if (value == SEPARATOR) {
                return Arrays.copyOf(key, size);
            }
            if (value < 0 || isTerminator(value)) {
                throw new IndexFileDeclined();
            }
            if (size == key.length) {
                key = Arrays.copyOf(key, size * 2);
            }
            key[size++] = (byte) value;
        }
    }

    void seek(long position) {
        readPosition = position;
        lineStart = 0;
        lineEnd = 0;
        scanned = 0;
        filled = 0;
        exhausted = false;
    }

    boolean nextLine() throws IOException {
        while (true) {
            for (var at = scanned; at < filled; at++) {
                if (isTerminator(buffer[at])) {
                    final var start = lineStart;
                    lineStart = at + 1;
                    if (at > start) {
                        scanned = lineStart;
                        lineEnd = at;
                        terminated = true;
                        currentStart = start;
                        return true;
                    }
                }
            }
            scanned = filled;
            if (exhausted) {
                if (lineStart < filled) {
                    final var start = lineStart;
                    lineEnd = filled;
                    lineStart = filled;
                    terminated = false;
                    currentStart = start;
                    return true;
                }
                return false;
            }
            fill();
        }
    }

    private void fill() throws IOException {
        if (lineStart > 0) {
            System.arraycopy(buffer, lineStart, buffer, 0, filled - lineStart);
            filled -= lineStart;
            scanned -= lineStart;
            lineStart = 0;
        }
        if (filled == buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        final var read = channel.read(ByteBuffer.wrap(buffer, filled, buffer.length - filled), readPosition);
        if (read <= 0) {
            exhausted = true;
            return;
        }
        readPosition += read;
        filled += read;
    }

    byte[] lineBytes() {
        return buffer;
    }

    int lineFrom() {
        return currentStart;
    }

    int lineTo() {
        return lineEnd;
    }

    boolean lineTerminated() {
        return terminated;
    }

    int separatorIn(int from, int to) {
        for (var at = from; at < to; at++) {
            if (buffer[at] == SEPARATOR) {
                return at;
            }
        }
        throw new IndexFileDeclined();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
