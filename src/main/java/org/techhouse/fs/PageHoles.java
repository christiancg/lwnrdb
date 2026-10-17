package org.techhouse.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.techhouse.config.Globals;

final class PageHoles {
    private static final byte HOLE = ' ';
    private static final int TERMINATOR_BYTES = Globals.NEWLINE.getBytes(StandardCharsets.UTF_8).length;
    private static final int BLANK_CHUNK = 8192;
    private static final byte[] SPACES = spaces();

    private PageHoles() {
    }

    static boolean isHole(byte[] line, int start) {
        return start < line.length && line[start] == HOLE;
    }

    static boolean isSkippable(byte[] bytes, int start, int end) {
        return end <= start || bytes[start] == HOLE;
    }

    private static byte[] spaces() {
        final var spaces = new byte[BLANK_CHUNK];
        Arrays.fill(spaces, HOLE);
        return spaces;
    }

    static void blank(FileChannel page, long position, long length) throws IOException {
        final var spaces = length - TERMINATOR_BYTES;
        var written = 0L;
        while (written < spaces) {
            final var buffer = ByteBuffer.wrap(SPACES, 0, (int) Math.min(BLANK_CHUNK, spaces - written));
            while (buffer.hasRemaining()) {
                written += page.write(buffer, position + written);
            }
        }
    }
}
