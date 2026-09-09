package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// Per-file read/write locks guaranteeing physical-I/O atomicity: a file's bytes are never read
// while they are being rewritten. This is the finer-grained tier below the collection-level
// locks in ResourceLocking, and is what makes dirty reads safe (a dirty read skips the
// collection lock but still serializes against the in-progress physical write of each file).
final class FileLocks {
    private static final Map<String, ReentrantReadWriteLock> fileLocks = new ConcurrentHashMap<>();

    private FileLocks() {
    }

    static ReentrantReadWriteLock lockFor(File file) {
        return fileLocks.computeIfAbsent(file.getAbsolutePath(), _ -> new ReentrantReadWriteLock());
    }

    static List<String> readAllLinesLocked(File file) throws IOException {
        final var lock = lockFor(file).readLock();
        lock.lock();
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } finally {
            lock.unlock();
        }
    }

    static void rewriteFileAtomically(Path path, List<String> lines) throws IOException {
        final var tmp = path.resolveSibling(path.getFileName() + ".repair");
        Files.write(tmp, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // ATOMIC_MOVE can fail across filesystems or on platforms that don't
            // support it; fall back to a non-atomic move.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
