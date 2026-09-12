package org.techhouse.fs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;

final class FieldIndexStore {
    private static final byte[] NEWLINE_BYTES = Globals.NEWLINE.getBytes(StandardCharsets.UTF_8);
    private static final byte SEPARATOR_BYTE = (byte) Globals.ID_SEPARATOR.charAt(0);

    private final FilePaths paths;

    FieldIndexStore(FilePaths paths) {
        this.paths = paths;
    }

    void writeIndexFile(String dbName, String collName, String fieldName,
            Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap) {
        for (var indexTypeList : indexEntryMap.entrySet()) {
            final var type = IndexKind.fileLabel(indexTypeList.getKey());
            appendEntries(paths.indexFile(dbName, collName, fieldName, type), indexTypeList.getValue());
        }
    }

    void writeHashIndexFile(String dbName, String collName, String fieldName, IndexKind kind,
            List<FieldIndexEntry<String>> entries) {
        if (entries.isEmpty()) {
            return;
        }
        appendEntries(paths.indexFile(dbName, collName, fieldName, kind.label()), entries);
    }

    void updateHashIndexFiles(String dbName, String collName, String fieldName, IndexKind kind,
            FieldIndexEntry<String> insertedEntry, FieldIndexEntry<String> removedEntry) throws IOException {
        final var indexFile = paths.indexFile(dbName, collName, fieldName, kind.label());
        if (removedEntry != null) {
            removeIndexLine(indexFile, removedEntry.getValue(), removedEntry);
        }
        if (insertedEntry != null) {
            upsertIndexLine(indexFile, insertedEntry.getValue(), insertedEntry);
        }
    }

    <T, K> void updateIndexFiles(String dbName, String collName, String fieldName, FieldIndexEntry<T> insertedEntry,
            FieldIndexEntry<K> removedEntry) throws IOException {
        if (removedEntry != null) {
            removeIndexLine(indexFileFor(dbName, collName, fieldName, removedEntry), getStringValue(removedEntry),
                    removedEntry);
        }
        if (insertedEntry != null) {
            upsertIndexLine(indexFileFor(dbName, collName, fieldName, insertedEntry), getStringValue(insertedEntry),
                    insertedEntry);
        }
    }

    boolean dropIndex(String dbName, String collName, String fieldName) {
        final var collFolder = paths.collectionFolder(dbName, collName);
        if (collFolder.exists()) {
            final var indexFiles = collFolder.listFiles((_, name) -> name.endsWith(Globals.INDEX_FILE_EXTENSION) && name
                    .contains(Globals.INDEX_FILE_NAME_SEPARATOR + fieldName + Globals.INDEX_FILE_NAME_SEPARATOR));
            if (indexFiles != null) {
                final var deleted = new ArrayList<Boolean>();
                for (var index : indexFiles) {
                    deleted.add(index.delete());
                }
                return deleted.stream().allMatch(aBoolean -> aBoolean);
            }
        }
        return false;
    }

    private File indexFileFor(String dbName, String collName, String fieldName, FieldIndexEntry<?> entry) {
        return paths.indexFile(dbName, collName, fieldName, IndexKind.fileLabel(entry.getValue().getClass()));
    }

    private void appendEntries(File indexFile, List<? extends FieldIndexEntry<?>> entries) {
        final var lock = FileLocks.lockFor(indexFile).writeLock();
        lock.lock();
        try (var writer = new BufferedWriter(new FileWriter(indexFile, StandardCharsets.UTF_8, true),
                Globals.BUFFER_SIZE)) {
            for (final var entry : entries) {
                writer.append(entry.toFileEntry());
                writer.append(Globals.NEWLINE);
            }
            if (entries.isEmpty()) {
                writer.append(Globals.NEWLINE);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    private void removeIndexLine(File indexFile, String value, FieldIndexEntry<?> entry) throws IOException {
        final var lock = FileLocks.lockFor(indexFile).writeLock();
        lock.lock();
        try (var writer = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
            final var wholeFile = readFully(writer);
            final var indexOfExisting = searchIndexValue(wholeFile, value);
            if (indexOfExisting >= 0) {
                shiftOtherEntries(writer, wholeFile, indexOfExisting);
                if (!entry.getIds().isEmpty()) {
                    writeLine(writer, entry.toFileEntry());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void upsertIndexLine(File indexFile, String value, FieldIndexEntry<?> entry) throws IOException {
        final var lock = FileLocks.lockFor(indexFile).writeLock();
        lock.lock();
        try (var writer = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
            final var wholeFile = readFully(writer);
            final var indexOfExisting = searchIndexValue(wholeFile, value);
            if (indexOfExisting >= 0) {
                shiftOtherEntries(writer, wholeFile, indexOfExisting);
            } else {
                writer.seek(wholeFile.length);
            }
            writeLine(writer, entry.toFileEntry());
        } finally {
            lock.unlock();
        }
    }

    private int searchIndexValue(byte[] wholeFile, String value) {
        final var target = value.getBytes(StandardCharsets.UTF_8);
        int lineStart = 0;
        while (lineStart < wholeFile.length) {
            final int lineEnd = indexOfNewline(wholeFile, lineStart);
            final int effectiveEnd = lineEnd == -1 ? wholeFile.length : lineEnd;
            if (!isBlankRegion(wholeFile, lineStart, effectiveEnd)) {
                final int separatorIdx = indexOfSeparator(wholeFile, lineStart, effectiveEnd);
                if (separatorIdx >= 0 && regionEquals(wholeFile, lineStart, separatorIdx, target)) {
                    return lineStart == 0 ? 0 : lineStart - NEWLINE_BYTES.length;
                }
            }
            if (lineEnd == -1)
                break;
            lineStart = lineEnd + NEWLINE_BYTES.length;
        }
        return -1;
    }

    private static int indexOfNewline(byte[] wholeFile, int from) {
        final int limit = wholeFile.length - NEWLINE_BYTES.length;
        for (int i = from; i <= limit; i++) {
            if (startsWithNewline(wholeFile, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWithNewline(byte[] wholeFile, int from) {
        if (from < 0 || from + NEWLINE_BYTES.length > wholeFile.length) {
            return false;
        }
        for (int i = 0; i < NEWLINE_BYTES.length; i++) {
            if (wholeFile[from + i] != NEWLINE_BYTES[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfSeparator(byte[] wholeFile, int from, int to) {
        for (int i = from; i < to; i++) {
            if (wholeFile[i] == SEPARATOR_BYTE) {
                return i;
            }
        }
        return -1;
    }

    private static boolean regionEquals(byte[] wholeFile, int from, int to, byte[] target) {
        if (to - from != target.length) {
            return false;
        }
        for (int i = 0; i < target.length; i++) {
            if (wholeFile[from + i] != target[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlankRegion(byte[] wholeFile, int from, int to) {
        for (int i = from; i < to; i++) {
            final var b = wholeFile[i];
            final var whitespace = b == ' ' || (b >= 0x09 && b <= 0x0D) || (b >= 0x1C && b <= 0x1F);
            if (!whitespace) {
                return false;
            }
        }
        return true;
    }

    private <K> String getStringValue(FieldIndexEntry<K> entry) {
        return FieldIndexEntry.indexKeyOf(entry.getValue());
    }

    private void shiftOtherEntries(RandomAccessFile writer, byte[] wholeFile, int indexOfExisting) throws IOException {
        var replacementIndex = indexOfExisting;
        if (startsWithNewline(wholeFile, replacementIndex)) {
            replacementIndex += NEWLINE_BYTES.length;
        }
        final int lineEnd = indexOfNewline(wholeFile, replacementIndex);
        final int tailStart = lineEnd == -1 ? wholeFile.length : lineEnd + NEWLINE_BYTES.length;
        final int tailLength = wholeFile.length - tailStart;
        writer.seek(replacementIndex);
        if (tailLength > 0) {
            writer.write(wholeFile, tailStart, tailLength);
        }
        writer.setLength((long) replacementIndex + tailLength);
    }

    private static void writeLine(RandomAccessFile writer, String line) throws IOException {
        writer.write(line.getBytes(StandardCharsets.UTF_8));
        writer.write(NEWLINE_BYTES);
    }

    private byte[] readFully(RandomAccessFile writer) throws IOException {
        final var fileLength = (int) writer.length();
        byte[] buffer = new byte[fileLength];
        writer.readFully(buffer, 0, fileLength);
        return buffer;
    }
}
