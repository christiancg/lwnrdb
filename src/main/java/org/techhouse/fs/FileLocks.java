package org.techhouse.fs;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;
import org.techhouse.utils.OwnerOnlyPermissions;

// The finer tier below ResourceLocking's collection locks, and what makes dirty reads safe: a
// dirty read skips the collection lock but still serializes against each file's physical write.
final class FileLocks {
    private static final Logger logger = Logger.logFor(FileLocks.class);

    private static final Map<String, ReentrantReadWriteLock> fileLocks = new ConcurrentHashMap<>();
    private static final byte LINE_FEED = '\n';
    private static final byte CARRIAGE_RETURN = '\r';
    private static final byte[] NEWLINE_BYTES = Globals.NEWLINE.getBytes(StandardCharsets.UTF_8);

    private FileLocks() {
    }

    record Parsed<E>(E entries, List<String> lines, boolean dropped, boolean unrecognised) {
    }

    interface Parser<E> {
        Parsed<E> parse(File file) throws IOException;
    }

    static <E> Parsed<E> readHealing(File file, Parser<E> parser) throws IOException {
        final var readLock = lockRead(file);
        final Parsed<E> parsed;
        try {
            parsed = parser.parse(file);
        } finally {
            readLock.unlock();
        }
        if (parsed == null || parsed.unrecognised() || !parsed.dropped()) {
            return parsed;
        }
        final var writeLock = lockWrite(file);
        try {
            final var reparsed = parser.parse(file);
            if (reparsed != null && reparsed.dropped() && !reparsed.unrecognised()) {
                rewriteFileAtomically(file.toPath(), reparsed.lines());
            }
            return reparsed;
        } finally {
            writeLock.unlock();
        }
    }

    static <T> void appendLines(File file, List<T> rows, Function<T, String> toLine, String rollbackFailure)
            throws IOException {
        final var lock = lockWrite(file);
        try {
            final var lengthBeforeAppend = file.length();
            var appended = false;
            try {
                OwnerOnlyPermissions.ensureOwnerOnlyFile(file.toPath());
                try (var writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8, true),
                        Globals.BUFFER_SIZE)) {
                    if (endsMidLine(file)) {
                        writer.newLine();
                    }
                    for (final var row : rows) {
                        writer.append(toLine.apply(row));
                        writer.newLine();
                    }
                }
                appended = true;
            } finally {
                if (!appended) {
                    truncateTo(file, lengthBeforeAppend, rollbackFailure);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    static ReentrantReadWriteLock lockFor(File file) {
        return fileLocks.computeIfAbsent(file.getAbsolutePath(), _ -> new ReentrantReadWriteLock());
    }

    static Lock lockRead(File file) {
        return acquire(file, ReentrantReadWriteLock::readLock);
    }

    static Lock lockWrite(File file) {
        return acquire(file, ReentrantReadWriteLock::writeLock);
    }

    private static Lock acquire(File file, Function<ReentrantReadWriteLock, Lock> side) {
        final var path = file.getAbsolutePath();
        while (true) {
            final var registered = fileLocks.computeIfAbsent(path, _ -> new ReentrantReadWriteLock());
            final var lock = side.apply(registered);
            lock.lock();
            if (fileLocks.get(path) == registered) {
                return lock;
            }
            lock.unlock();
        }
    }

    static void evictIfUnused(File file) {
        evictIfUnused(file.getAbsolutePath());
    }

    static void evictAllUnder(File folder) {
        final var prefix = folder.getAbsolutePath() + File.separator;
        for (final var path : List.copyOf(fileLocks.keySet())) {
            if (path.startsWith(prefix)) {
                evictIfUnused(path);
            }
        }
    }

    private static void evictIfUnused(String path) {
        final var retired = new ReentrantReadWriteLock[1];
        fileLocks.computeIfPresent(path, (_, lock) -> {
            if (lock.isWriteLocked() || lock.getReadLockCount() > 0 || lock.hasQueuedThreads()
                    || !lock.writeLock().tryLock()) {
                return lock;
            }
            retired[0] = lock;
            return null;
        });
        if (retired[0] != null) {
            retired[0].writeLock().unlock();
        }
    }

    static List<String> readAllLinesIfExists(File file) throws IOException {
        final var lock = lockRead(file);
        try {
            return decodeLines(Files.readAllBytes(file.toPath()));
        } catch (NoSuchFileException e) {
            return null;
        } finally {
            lock.unlock();
        }
    }

    static List<String> readAllLinesLocked(File file) throws IOException {
        final var lock = lockRead(file);
        try {
            return decodeLines(Files.readAllBytes(file.toPath()));
        } finally {
            lock.unlock();
        }
    }

    static List<String> decodeLines(byte[] bytes) {
        final var lines = new ArrayList<String>();
        var start = 0;
        var i = 0;
        while (i < bytes.length) {
            final var current = bytes[i];
            if (current == LINE_FEED || current == CARRIAGE_RETURN) {
                lines.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                i++;
                if (current == CARRIAGE_RETURN && i < bytes.length && bytes[i] == LINE_FEED) {
                    i++;
                }
                start = i;
            } else {
                i++;
            }
        }
        if (start < bytes.length) {
            lines.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
        }
        return lines;
    }

    static void rewriteFileAtomically(Path path, List<String> lines) throws IOException {
        rewriteFileAtomically(path, out -> {
            for (final var line : lines) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write(NEWLINE_BYTES);
            }
        });
    }

    static void rewriteFileAtomically(Path path, LineWriter content) throws IOException {
        final var tmp = path.resolveSibling(path.getFileName() + ".repair");
        OwnerOnlyPermissions.ensureOwnerOnlyFile(tmp);
        try (var out = new BufferedOutputStream(Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), Globals.BUFFER_SIZE)) {
            content.writeTo(out);
        }
        moveIntoPlace(tmp, path);
    }

    static void rewriteFileAtomically(Path path, byte[] content) throws IOException {
        final var tmp = path.resolveSibling(path.getFileName() + ".repair");
        OwnerOnlyPermissions.ensureOwnerOnlyFile(tmp);
        Files.write(tmp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        moveIntoPlace(tmp, path);
    }

    private static void moveIntoPlace(Path tmp, Path path) throws IOException {
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // ATOMIC_MOVE is not supported across filesystems or on every platform.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String separatorBeforeAppend(File file) throws IOException {
        return endsMidLine(file) ? Globals.NEWLINE : "";
    }

    static boolean endsMidLine(File file) throws IOException {
        if (!file.exists() || file.length() == 0) {
            return false;
        }
        try (var raf = new RandomAccessFile(file, "r")) {
            raf.seek(raf.length() - 1);
            return raf.readByte() != LINE_FEED;
        }
    }

    static void truncateTo(File file, long length) {
        truncateTo(file, length, "Could not roll back the page append for " + file.getAbsolutePath()
                + " after its index write failed; run REINDEX on this collection");
    }

    static void truncateTo(File file, long length, String failureMessage) {
        try (var channel = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            channel.setLength(length);
        } catch (IOException e) {
            logger.error(failureMessage, e);
        }
    }
}
