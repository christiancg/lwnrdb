package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.techhouse.config.Globals;

// The finer tier below ResourceLocking's collection locks, and what makes dirty reads safe: a
// dirty read skips the collection lock but still serializes against each file's physical write.
final class FileLocks {
    private static final Map<String, ReentrantReadWriteLock> fileLocks = new ConcurrentHashMap<>();

    private FileLocks() {
    }

    static ReentrantReadWriteLock lockFor(File file) {
        return fileLocks.computeIfAbsent(file.getAbsolutePath(), _ -> new ReentrantReadWriteLock());
    }

    static List<String> readAllLinesIfExists(File file) throws IOException {
        final var lock = lockFor(file).readLock();
        lock.lock();
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } finally {
            lock.unlock();
        }
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
        var chars = 0;
        for (final var line : lines) {
            chars += line.length() + Globals.NEWLINE.length();
        }
        final var builder = new StringBuilder(chars);
        for (final var line : lines) {
            builder.append(line).append(Globals.NEWLINE);
        }
        rewriteFileAtomically(path, builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void rewriteFileAtomically(Path path, byte[] content) throws IOException {
        final var tmp = path.resolveSibling(path.getFileName() + ".repair");
        Files.write(tmp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // ATOMIC_MOVE is not supported across filesystems or on every platform.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
