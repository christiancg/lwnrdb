package org.techhouse.fs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

final class DurableShadowKeys {
    private static final int PIN_ATTEMPTS = 3;
    private static final int READ_CHUNK = 64 * 1024;

    private final Map<Object, PinnedKey> pinned = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    record PinnedKey(String id, String fileKey, FileChannel channel, ReentrantLock lock) {
        byte[] readAll() throws IOException {
            final var out = new ByteArrayOutputStream();
            final var buffer = ByteBuffer.allocate(READ_CHUNK);
            long position = 0;
            while (true) {
                buffer.clear();
                final var read = channel.read(buffer, position);
                if (read <= 0) {
                    return out.toByteArray();
                }
                out.write(buffer.array(), 0, read);
                position += read;
            }
        }
    }

    PinnedKey pin(Path path) throws IOException {
        for (var attempt = 0; attempt < PIN_ATTEMPTS; attempt++) {
            final var key = fileKeyOf(path);
            final var known = pinned.get(key);
            if (known != null) {
                return known;
            }
            final var channel = FileChannel.open(path, StandardOpenOption.READ);
            if (stillAt(path, key, channel)) {
                final var fresh = new PinnedKey(String.valueOf(ids.incrementAndGet()), String.valueOf(key), channel,
                        new ReentrantLock());
                final var raced = pinned.putIfAbsent(key, fresh);
                if (raced == null) {
                    return fresh;
                }
                channel.close();
                return raced;
            }
            channel.close();
        }
        throw new NoSuchFileException(path.toString(), null, "replaced while it was being pinned");
    }

    private static boolean stillAt(Path path, Object key, FileChannel channel) throws IOException {
        try {
            return key.equals(fileKeyOf(path));
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    void close() throws IOException {
        for (final var key : pinned.values()) {
            key.channel().close();
        }
        pinned.clear();
    }

    private static Object fileKeyOf(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }
}
