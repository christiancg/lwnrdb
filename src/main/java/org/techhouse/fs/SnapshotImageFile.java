package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import org.techhouse.fs.io.ChunkedChannelWrites;

public final class SnapshotImageFile implements AutoCloseable {
    private final File file;
    private FileChannel channel;
    private long length;

    SnapshotImageFile(File file) {
        this.file = file;
    }

    public synchronized long append(byte[] line) throws IOException {
        final var offset = length;
        ensureOpen();
        ChunkedChannelWrites.writeAt(channel, offset, line, 0, line.length);
        length += line.length;
        return offset;
    }

    public synchronized byte[] read(long offset, int lineLength) throws IOException {
        final var line = new byte[lineLength];
        final var buffer = ByteBuffer.wrap(line);
        ensureOpen();
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) {
                throw new IOException(
                        "Snapshot image file " + file.getName() + " ends before offset " + (offset + lineLength));
            }
        }
        return line;
    }

    public synchronized long bytes() {
        return length;
    }

    @Override
    public synchronized void close() throws IOException {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    private void ensureOpen() throws IOException {
        if (channel == null) {
            OwnerOnly.createDirectories(file.getParentFile().toPath());
            OwnerOnly.createNewOwnerOnlyFile(file.toPath());
            channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
    }
}
