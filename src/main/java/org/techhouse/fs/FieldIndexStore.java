package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.log.Logger;

final class FieldIndexStore {
    private static final Logger logger = Logger.logFor(FieldIndexStore.class);
    private static final byte[] NEWLINE_BYTES = Globals.NEWLINE.getBytes(StandardCharsets.UTF_8);
    private static final byte LINE_FEED = '\n';
    private static final byte CARRIAGE_RETURN = '\r';
    private static final byte SEPARATOR_BYTE = (byte) Globals.ID_SEPARATOR.charAt(0);

    private final FilePaths paths;

    FieldIndexStore(FilePaths paths) {
        this.paths = paths;
    }

    void writeIndexFile(String dbName, String collName, String fieldName,
            Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap) {
        for (var indexTypeList : indexEntryMap.entrySet()) {
            final var type = IndexKind.fileLabel(indexTypeList.getKey());
            writeEntries(paths.indexFile(dbName, collName, fieldName, type), indexTypeList.getValue());
        }
    }

    void writeHashIndexFile(String dbName, String collName, String fieldName, IndexKind kind,
            List<FieldIndexEntry<String>> entries) {
        if (entries.isEmpty()) {
            return;
        }
        writeEntries(paths.indexFile(dbName, collName, fieldName, kind.label()), entries);
    }

    void updateHashIndexFiles(String dbName, String collName, String fieldName, IndexKind kind,
            FieldIndexEntry<String> insertedEntry, FieldIndexEntry<String> removedEntry) throws IOException {
        final var indexFile = paths.indexFile(dbName, collName, fieldName, kind.label());
        if (removedEntry != null) {
            removeIndexLine(indexFile, getStringValue(removedEntry), removedEntry);
        }
        if (insertedEntry != null) {
            upsertIndexLine(indexFile, getStringValue(insertedEntry), insertedEntry);
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
            final var prefix = collName + Globals.INDEX_FILE_NAME_SEPARATOR + fieldName
                    + Globals.INDEX_FILE_NAME_SEPARATOR;
            final var indexFiles = collFolder.listFiles((_, name) -> namesOneTypeOfThisField(name, prefix));
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

    private static boolean namesOneTypeOfThisField(String fileName, String prefix) {
        if (!fileName.endsWith(Globals.INDEX_FILE_EXTENSION) || !fileName.startsWith(prefix)) {
            return false;
        }
        final var typeSegment = fileName.substring(prefix.length(),
                fileName.length() - Globals.INDEX_FILE_EXTENSION.length());
        return !typeSegment.isEmpty() && typeSegment.indexOf(Globals.INDEX_FILE_NAME_SEPARATOR) < 0;
    }

    private File indexFileFor(String dbName, String collName, String fieldName, FieldIndexEntry<?> entry) {
        return paths.indexFile(dbName, collName, fieldName, IndexKind.fileLabel(entry.getValue().getClass()));
    }

    private void writeEntries(File indexFile, List<? extends FieldIndexEntry<?>> entries) {
        if (indexFile == null) {
            return;
        }
        final var content = new StringBuilder();
        for (final var entry : entries) {
            content.append(entry.toFileEntry()).append(Globals.NEWLINE);
        }
        if (entries.isEmpty()) {
            content.append(Globals.NEWLINE);
        }
        final var lock = FileLocks.lockFor(indexFile).writeLock();
        lock.lock();
        try {
            FileLocks.rewriteFileAtomically(indexFile.toPath(), content.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    private void removeIndexLine(File indexFile, String value, FieldIndexEntry<?> entry) throws IOException {
        if (indexFile == null) {
            return;
        }
        final var lock = FileLocks.lockFor(indexFile).writeLock();
        lock.lock();
        try {
            final var wholeFile = readFully(indexFile);
            final var indexOfExisting = searchIndexValue(wholeFile, value);
            if (indexOfExisting < 0) {
                if (wholeFile.length == 0 && indexFile.exists()) {
                    deleteIndexFile(indexFile);
                }
                return;
            }
            final var withoutExisting = withoutLine(wholeFile, indexOfExisting);
            final var content = entry.getIds().isEmpty()
                    ? withoutExisting
                    : appendLine(withoutExisting, entry.toFileEntry());
            if (content.length == 0) {
                deleteIndexFile(indexFile);
            } else {
                FileLocks.rewriteFileAtomically(indexFile.toPath(), content);
            }
        } finally {
            lock.unlock();
        }
    }

    private static void deleteIndexFile(File indexFile) {
        if (!indexFile.delete()) {
            logger.warning("Could not delete the now-empty index file " + indexFile.getName()
                    + "; while it exists CONTAINS and NOT_IN decline this field's index until a REINDEX");
        }
    }

    private void upsertIndexLine(File indexFile, String value, FieldIndexEntry<?> entry) throws IOException {
        if (indexFile == null) {
            return;
        }
        final var lock = FileLocks.lockFor(indexFile).writeLock();
        lock.lock();
        try {
            final var wholeFile = readFully(indexFile);
            final var indexOfExisting = searchIndexValue(wholeFile, value);
            final var base = indexOfExisting >= 0 ? withoutLine(wholeFile, indexOfExisting) : wholeFile;
            FileLocks.rewriteFileAtomically(indexFile.toPath(), appendLine(base, entry.toFileEntry()));
        } finally {
            lock.unlock();
        }
    }

    private int searchIndexValue(byte[] wholeFile, String value) {
        final var target = value.getBytes(StandardCharsets.UTF_8);
        int lineStart = 0;
        while (lineStart < wholeFile.length) {
            final int lineFeed = indexOfLineFeed(wholeFile, lineStart);
            final int contentEnd = contentEndOf(wholeFile, lineStart, lineFeed);
            if (!isBlankRegion(wholeFile, lineStart, contentEnd)) {
                final int separatorIdx = indexOfSeparator(wholeFile, lineStart, contentEnd);
                if (separatorIdx >= 0 && regionEquals(wholeFile, lineStart, separatorIdx, target)) {
                    return lineStart;
                }
            }
            if (lineFeed == -1)
                break;
            lineStart = lineFeed + 1;
        }
        return -1;
    }

    private static int indexOfLineFeed(byte[] wholeFile, int from) {
        for (int i = from; i < wholeFile.length; i++) {
            if (wholeFile[i] == LINE_FEED) {
                return i;
            }
        }
        return -1;
    }

    private static int contentEndOf(byte[] wholeFile, int lineStart, int lineFeed) {
        final int end = lineFeed == -1 ? wholeFile.length : lineFeed;
        return end > lineStart && wholeFile[end - 1] == CARRIAGE_RETURN ? end - 1 : end;
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
        return FieldIndexEntry.fileKeyOf(entry.getValue());
    }

    private static byte[] withoutLine(byte[] wholeFile, int lineStart) {
        final int lineFeed = indexOfLineFeed(wholeFile, lineStart);
        final int tailStart = lineFeed == -1 ? wholeFile.length : lineFeed + 1;
        final int tailLength = wholeFile.length - tailStart;
        final var content = new byte[lineStart + tailLength];
        System.arraycopy(wholeFile, 0, content, 0, lineStart);
        System.arraycopy(wholeFile, tailStart, content, lineStart, tailLength);
        return content;
    }

    private static byte[] appendLine(byte[] content, String line) {
        final var separator = content.length > 0 && content[content.length - 1] != LINE_FEED
                ? NEWLINE_BYTES
                : new byte[0];
        final var lineBytes = line.getBytes(StandardCharsets.UTF_8);
        final var result = new byte[content.length + separator.length + lineBytes.length + NEWLINE_BYTES.length];
        var offset = 0;
        for (final var part : List.of(content, separator, lineBytes, NEWLINE_BYTES)) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static byte[] readFully(File indexFile) throws IOException {
        return indexFile.exists() ? Files.readAllBytes(indexFile.toPath()) : new byte[0];
    }
}
