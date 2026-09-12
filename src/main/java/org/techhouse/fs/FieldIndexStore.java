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
import java.util.stream.Collectors;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonCustom;

final class FieldIndexStore {
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
            var strData = entries.stream().map(FieldIndexEntry::toFileEntry)
                    .collect(Collectors.joining(Globals.NEWLINE));
            strData += Globals.NEWLINE;
            writer.append(strData);
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
            final var strWholeFile = readFully(writer);
            final var indexOfExisting = searchIndexValue(strWholeFile, value);
            if (indexOfExisting >= 0) {
                shiftOtherEntries(writer, strWholeFile, indexOfExisting);
                if (!entry.getIds().isEmpty()) {
                    writer.writeBytes(entry.toFileEntry());
                    writer.writeBytes(Globals.NEWLINE);
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
            final var strWholeFile = readFully(writer);
            final var indexOfExisting = searchIndexValue(strWholeFile, value);
            if (indexOfExisting >= 0) {
                shiftOtherEntries(writer, strWholeFile, indexOfExisting);
            } else {
                writer.seek(strWholeFile.length());
            }
            writer.writeBytes(entry.toFileEntry());
            writer.writeBytes(Globals.NEWLINE);
        } finally {
            lock.unlock();
        }
    }

    private int searchIndexValue(String strWholeFile, String value) {
        int lineStart = 0;
        while (lineStart < strWholeFile.length()) {
            final int lineEnd = strWholeFile.indexOf(Globals.NEWLINE, lineStart);
            final int effectiveEnd = lineEnd == -1 ? strWholeFile.length() : lineEnd;
            final var line = strWholeFile.substring(lineStart, effectiveEnd);
            if (!line.isBlank()) {
                final var separatorIdx = line.indexOf(Globals.ID_SEPARATOR);
                if (separatorIdx >= 0 && line.substring(0, separatorIdx).equals(value)) {
                    return lineStart == 0 ? 0 : lineStart - Globals.NEWLINE_CHAR_LENGTH;
                }
            }
            if (lineEnd == -1)
                break;
            lineStart = lineEnd + Globals.NEWLINE_CHAR_LENGTH;
        }
        return -1;
    }

    private <K> String getStringValue(FieldIndexEntry<K> entry) {
        final var value = entry.getValue();
        String strValue;
        if (value instanceof JsonCustom<?> jsonCustom) {
            strValue = jsonCustom.getValue();
        } else if (value instanceof Number number) {
            if (number.doubleValue() % 1 == 0) {
                strValue = String.valueOf(number.longValue());
            } else {
                strValue = String.valueOf(number.doubleValue());
            }
        } else {
            strValue = value.toString();
        }
        return strValue;
    }

    private void shiftOtherEntries(RandomAccessFile writer, String strWholeFile, int indexOfExisting)
            throws IOException {
        var replacementIndex = indexOfExisting;
        var fromExistingEntry = strWholeFile.substring(indexOfExisting);
        if (fromExistingEntry.startsWith(Globals.NEWLINE)) {
            fromExistingEntry = fromExistingEntry.substring(Globals.NEWLINE_CHAR_LENGTH);
            replacementIndex += Globals.NEWLINE_CHAR_LENGTH;
        }
        var otherEntries = fromExistingEntry.substring(fromExistingEntry.indexOf(Globals.NEWLINE));
        if (otherEntries.startsWith(Globals.NEWLINE)) {
            otherEntries = otherEntries.substring(Globals.NEWLINE_CHAR_LENGTH);
        }
        writer.seek(replacementIndex);
        writer.writeBytes(otherEntries);
        writer.setLength(replacementIndex + otherEntries.length());
    }

    private String readFully(RandomAccessFile writer) throws IOException {
        final var fileLength = (int) writer.length();
        byte[] buffer = new byte[fileLength];
        writer.readFully(buffer, 0, fileLength);
        return new String(buffer, StandardCharsets.UTF_8);
    }
}
