package org.techhouse.fs;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.StringReader;
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
import java.util.function.Function;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;

// The finer tier below ResourceLocking's collection locks, and what makes dirty reads safe: a
// dirty read skips the collection lock but still serializes against each file's physical write.
final class FileLocks {
    private static final Logger logger = Logger.logFor(FileLocks.class);

    private static final Map<String, ReentrantReadWriteLock> fileLocks = new ConcurrentHashMap<>();
    private static final byte LINE_FEED = '\n';

    private FileLocks() {
    }

    record Parsed<E>(E entries, List<String> lines, boolean dropped, boolean unrecognised) {
    }

    interface Parser<E> {
        Parsed<E> parse(File file) throws IOException;
    }

    static <E> Parsed<E> readHealing(File file, Parser<E> parser) throws IOException {
        final var readLock = lockFor(file).readLock();
        readLock.lock();
        final Parsed<E> parsed;
        try {
            parsed = parser.parse(file);
        } finally {
            readLock.unlock();
        }
        if (parsed == null || parsed.unrecognised() || !parsed.dropped()) {
            return parsed;
        }
        final var writeLock = lockFor(file).writeLock();
        writeLock.lock();
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
        final var lock = lockFor(file).writeLock();
        lock.lock();
        try {
            final var lengthBeforeAppend = file.length();
            var appended = false;
            try {
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

    static List<String> readAllLinesIfExists(File file) throws IOException {
        final var lock = lockFor(file).readLock();
        lock.lock();
        try {
            return decodeLines(Files.readAllBytes(file.toPath()));
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
            return decodeLines(Files.readAllBytes(file.toPath()));
        } finally {
            lock.unlock();
        }
    }

    static List<String> decodeLines(byte[] bytes) throws IOException {
        try (var reader = new BufferedReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            return reader.lines().toList();
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
