package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.test.TestUtils;

public final class LegacyIndexLines {
    private static final byte[] NEWLINE_BYTES = Globals.NEWLINE.getBytes(StandardCharsets.UTF_8);
    private static final byte LINE_FEED = '\n';
    private static final byte CARRIAGE_RETURN = '\r';
    private static final byte SEPARATOR_BYTE = (byte) Globals.ID_SEPARATOR.charAt(0);

    private final FilePaths paths;

    LegacyIndexLines(FilePaths paths) {
        this.paths = paths;
    }

    public static LegacyIndexLines of(FileSystem fileSystem) {
        try {
            return new LegacyIndexLines(TestUtils.getPrivateField(fileSystem, "paths", FilePaths.class));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public void updateHashIndexFiles(String dbName, String collName, String fieldName, IndexKind kind,
            FieldIndexEntry<String> insertedEntry, FieldIndexEntry<String> removedEntry) throws IOException {
        final var indexFile = paths.indexFile(dbName, collName, fieldName, kind.label());
        if (removedEntry != null) {
            removeIndexLine(indexFile, FieldIndexEntry.fileKeyOf(removedEntry.getValue()), removedEntry);
        }
        if (insertedEntry != null) {
            upsertIndexLine(indexFile, FieldIndexEntry.fileKeyOf(insertedEntry.getValue()), insertedEntry);
        }
    }

    public <T, K> void updateIndexFiles(String dbName, String collName, String fieldName,
            FieldIndexEntry<T> insertedEntry, FieldIndexEntry<K> removedEntry) throws IOException {
        if (removedEntry != null) {
            removeIndexLine(indexFileFor(dbName, collName, fieldName, removedEntry),
                    FieldIndexEntry.fileKeyOf(removedEntry.getValue()), removedEntry);
        }
        if (insertedEntry != null) {
            upsertIndexLine(indexFileFor(dbName, collName, fieldName, insertedEntry),
                    FieldIndexEntry.fileKeyOf(insertedEntry.getValue()), insertedEntry);
        }
    }

    private File indexFileFor(String dbName, String collName, String fieldName, FieldIndexEntry<?> entry) {
        return paths.indexFile(dbName, collName, fieldName, IndexKind.fileLabel(entry.getValue().getClass()));
    }

    private static void removeIndexLine(File indexFile, String value, FieldIndexEntry<?> entry) throws IOException {
        if (indexFile == null) {
            return;
        }
        final var lock = FileLocks.lockWrite(indexFile);
        try {
            final var wholeFile = readFully(indexFile);
            final var indexOfExisting = searchIndexValue(wholeFile, value);
            if (indexOfExisting < 0) {
                if (wholeFile.length == 0 && indexFile.exists()) {
                    Files.delete(indexFile.toPath());
                }
                return;
            }
            final var withoutExisting = withoutLine(wholeFile, indexOfExisting);
            final var content = entry.getIds().isEmpty()
                    ? withoutExisting
                    : appendLine(withoutExisting, entry.toFileEntry());
            if (content.length == 0) {
                Files.delete(indexFile.toPath());
            } else {
                FileLocks.rewriteFileAtomically(indexFile.toPath(), content);
            }
        } finally {
            lock.unlock();
        }
    }

    private static void upsertIndexLine(File indexFile, String value, FieldIndexEntry<?> entry) throws IOException {
        if (indexFile == null) {
            return;
        }
        final var lock = FileLocks.lockWrite(indexFile);
        try {
            final var wholeFile = readFully(indexFile);
            final var indexOfExisting = searchIndexValue(wholeFile, value);
            final var base = indexOfExisting >= 0 ? withoutLine(wholeFile, indexOfExisting) : wholeFile;
            FileLocks.rewriteFileAtomically(indexFile.toPath(), appendLine(base, entry.toFileEntry()));
        } finally {
            lock.unlock();
        }
    }

    private static int searchIndexValue(byte[] wholeFile, String value) {
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
            if (lineFeed == -1) {
                break;
            }
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
