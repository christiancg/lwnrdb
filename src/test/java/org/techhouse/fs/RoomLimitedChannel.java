package org.techhouse.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

final class RoomLimitedChannel extends FileChannel {
    static final String NO_SPACE = "No space left on device";

    private final FileChannel delegate;
    private final long writableEnd;

    RoomLimitedChannel(FileChannel delegate, long writableEnd) {
        this.delegate = delegate;
        this.writableEnd = writableEnd;
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        final var allowed = Math.clamp(writableEnd - position, 0, src.remaining());
        if (allowed == 0 && src.hasRemaining()) {
            throw new IOException(NO_SPACE);
        }
        final var fitting = src.slice(src.position(), allowed);
        final var written = delegate.write(fitting, position);
        src.position(src.position() + written);
        return written;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        final var written = write(src, delegate.position());
        delegate.position(delegate.position() + written);
        return written;
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        var total = 0L;
        for (var index = offset; index < offset + length; index++) {
            total += write(srcs[index]);
        }
        return total;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return delegate.read(dst);
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        return delegate.read(dsts, offset, length);
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        return delegate.read(dst, position);
    }

    @Override
    public long position() throws IOException {
        return delegate.position();
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        delegate.position(newPosition);
        return this;
    }

    @Override
    public long size() throws IOException {
        return delegate.size();
    }

    @Override
    public FileChannel truncate(long size) throws IOException {
        delegate.truncate(size);
        return this;
    }

    @Override
    public void force(boolean metaData) throws IOException {
        delegate.force(metaData);
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        return delegate.transferTo(position, count, target);
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
        throw new IOException(NO_SPACE);
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        return delegate.map(mode, position, size);
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) throws IOException {
        return delegate.lock(position, size, shared);
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) throws IOException {
        return delegate.tryLock(position, size, shared);
    }

    @Override
    protected void implCloseChannel() throws IOException {
        delegate.close();
    }
}
