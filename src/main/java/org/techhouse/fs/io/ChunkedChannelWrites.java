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

    public static void append(FileChannel channel, byte[] header, byte[] payload, int payloadLength)
            throws IOException {
        final var firstChunk = Math.clamp(CHUNK_BYTES - header.length, 0, payloadLength);
        final var buffers = new ByteBuffer[]{ByteBuffer.wrap(header), ByteBuffer.wrap(payload, 0, firstChunk)};
        var unwritten = (long) header.length + firstChunk;
        while (unwritten > 0) {
            unwritten -= channel.write(buffers);
        }
        var written = firstChunk;
        while (written < payloadLength) {
            final var chunk = ByteBuffer.wrap(payload, written, Math.min(CHUNK_BYTES, payloadLength - written));
            while (chunk.hasRemaining()) {
                written += channel.write(chunk);
            }
        }
    }
}
