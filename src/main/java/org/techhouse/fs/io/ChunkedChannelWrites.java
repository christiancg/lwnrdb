package org.techhouse.fs.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public final class ChunkedChannelWrites {
    public static final int CHUNK_BYTES = 128 * 1024;

    private ChunkedChannelWrites() {
    }

    public static void writeAt(FileChannel channel, long position, byte[] bytes, int from, int length)
            throws IOException {
        var written = 0;
        while (written < length) {
            final var chunk = ByteBuffer.wrap(bytes, from + written, Math.min(CHUNK_BYTES, length - written));
            while (chunk.hasRemaining()) {
                written += channel.write(chunk, position + written);
            }
        }
    }
}
