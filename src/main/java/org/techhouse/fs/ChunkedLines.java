package org.techhouse.fs;

import java.util.Arrays;
import java.util.List;

final class ChunkedLines {
    static final int CHUNK_BYTES = 256 * 1024;
    private static final int MIN_CARRY_BYTES = 1024;
    private static final byte LINE_FEED = '\n';
    private static final byte CARRIAGE_RETURN = '\r';

    interface LineSink {
        void line(byte[] bytes, int start, int end);
    }

    private final LineSink sink;
    private byte[] carry;
    private int carried;
    private boolean lineFeedMayFollow;

    private ChunkedLines(LineSink sink) {
        this.sink = sink;
    }

    static void forEachLine(List<byte[]> chunks, LineSink sink) {
        final var lines = new ChunkedLines(sink);
        for (final var chunk : chunks) {
            lines.accept(chunk);
        }
        lines.finish();
    }

    private void accept(byte[] chunk) {
        if (chunk.length == 0) {
            return;
        }
        var start = lineFeedMayFollow && chunk[0] == LINE_FEED ? 1 : 0;
        lineFeedMayFollow = false;
        while (start < chunk.length) {
            final var end = FileLocks.lineEnd(chunk, start);
            if (end == chunk.length) {
                keep(chunk, start, end);
                return;
            }
            if (carried > 0) {
                keep(chunk, start, end);
                sink.line(carry, 0, carried);
                carried = 0;
            } else {
                sink.line(chunk, start, end);
            }
            lineFeedMayFollow = chunk[end] == CARRIAGE_RETURN && end + 1 == chunk.length;
            start = FileLocks.nextLineStart(chunk, end);
        }
    }

    private void keep(byte[] chunk, int from, int to) {
        final var needed = carried + to - from;
        if (carry == null || carry.length < needed) {
            final var grown = Math.max(needed, carry == null ? MIN_CARRY_BYTES : carry.length * 2);
            carry = carry == null ? new byte[grown] : Arrays.copyOf(carry, grown);
        }
        System.arraycopy(chunk, from, carry, carried, to - from);
        carried = needed;
    }

    private void finish() {
        if (carried > 0) {
            sink.line(carry, 0, carried);
            carried = 0;
        }
    }
}
