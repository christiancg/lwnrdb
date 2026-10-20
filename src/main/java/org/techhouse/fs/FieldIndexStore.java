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

    boolean appendIndexLog(String dbName, String collName, String fieldName, String typeLabel,
            List<IndexLogChange> changes, List<? extends FieldIndexEntry<?>> survivors) throws IOException {
        final var indexFile = paths.indexFile(dbName, collName, fieldName, typeLabel);
        if (indexFile == null) {
            return false;
        }
        final var logFile = FilePaths.logFileOf(indexFile);
        final var lock = FileLocks.lockWrite(indexFile);
        try {
            if (survivors.isEmpty() || FieldIndexLog.append(indexFile, logFile, changes)) {
                replaceType(indexFile, logFile, survivors);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    static void replaceType(File indexFile, File logFile, List<? extends FieldIndexEntry<?>> entries)
            throws IOException {
        if (entries.isEmpty()) {
            deleteIndexFile(indexFile);
            deleteIndexFile(logFile);
            return;
        }
        FileLocks.rewriteFileAtomically(indexFile.toPath(), out -> {
            final var lines = new IndexLineWriter(out);
            for (final var entry : entries) {
                lines.write(entry);
            }
        });
        if (Files.deleteIfExists(logFile.toPath())) {
            syncFolderOf(logFile);
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
                    FileLocks.evictIfUnused(index);
                }
                SyncedWrites.entriesOfLanded(collFolder.toPath(), "Dropping the index files of " + fieldName);
                return deleted.stream().allMatch(aBoolean -> aBoolean);
            }
        }
        return false;
    }

    private static boolean namesOneTypeOfThisField(String fileName, String prefix) {
        final var extension = fileName.endsWith(Globals.INDEX_LOG_FILE_EXTENSION)
                ? Globals.INDEX_LOG_FILE_EXTENSION
                : Globals.INDEX_FILE_EXTENSION;
        if (!fileName.endsWith(extension) || !fileName.startsWith(prefix)) {
            return false;
        }
        final var typeSegment = fileName.substring(prefix.length(), fileName.length() - extension.length());
        return !typeSegment.isEmpty() && typeSegment.indexOf(Globals.INDEX_FILE_NAME_SEPARATOR) < 0;
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
        final var lock = FileLocks.lockWrite(indexFile);
        try {
            FileLocks.rewriteFileAtomically(indexFile.toPath(), content.toString().getBytes(StandardCharsets.UTF_8));
            if (Files.deleteIfExists(FilePaths.logFileOf(indexFile).toPath())) {
                syncFolderOf(indexFile);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    private static void deleteIndexFile(File indexFile) {
        if (!indexFile.exists()) {
            return;
        }
        if (indexFile.delete()) {
            syncFolderOf(indexFile);
        } else {
            logger.warning("Could not delete the now-empty index file " + indexFile.getName()
                    + "; while it exists CONTAINS and NOT_IN decline this field's index until a REINDEX");
        }
    }

    private static void syncFolderOf(File file) {
        SyncedWrites.entriesOfLanded(file.getAbsoluteFile().getParentFile().toPath(), "Deleting " + file.getName());
    }
}
