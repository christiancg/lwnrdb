package org.techhouse.fs;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.utils.OwnerOnlyPermissions;

final class PageAppender {
    private final FilePaths paths;
    private final PkIndexStore pkIndexStore;
    private final PageVacuum vacuum;

    PageAppender(FilePaths paths, PkIndexStore pkIndexStore, PageVacuum vacuum) {
        this.paths = paths;
        this.pkIndexStore = pkIndexStore;
        this.vacuum = vacuum;
    }

    <T extends DbEntry> List<IndexedDbEntry> bulkInsert(String dbName, String collName, List<T> entries,
            Function<? super T, Long> storagePageResolver, Function<? super T, byte[]> encodedLines)
            throws IOException {
        final var indexEntries = new ArrayList<IndexedDbEntry>();
        final var pkEntriesToIndex = new ArrayList<PkIndexEntry>();
        final var lengthsBeforeAppend = new LinkedHashMap<File, Long>();
        final var entrySet = entries.stream().collect(Collectors.groupingBy(storagePageResolver)).entrySet();
        try {
            for (var groupedEntry : entrySet) {
                final var page = groupedEntry.getKey();
                final var file = paths.collectionPage(dbName, collName, page);
                OwnerOnlyPermissions.ensureOwnerOnlyFile(file.toPath());
                final var lock = FileLocks.lockWrite(file);
                try {
                    vacuum.vacuumIfNeeded(dbName, collName, page, appendedBytes(groupedEntry.getValue()), null, null);
                    lengthsBeforeAppend.putIfAbsent(file, file.length());
                    appendGroup(dbName, collName, page, file, groupedEntry.getValue(), encodedLines, pkEntriesToIndex,
                            indexEntries);
                } finally {
                    lock.unlock();
                }
            }
            pkIndexStore.appendRows(dbName, collName, pkEntriesToIndex);
        } catch (IOException e) {
            rollBackBulkAppends(lengthsBeforeAppend);
            throw e;
        }
        return indexEntries;
    }

    private static <T extends DbEntry> void appendGroup(String dbName, String collName, long page, File file,
            List<T> entries, Function<? super T, byte[]> encodedLines, List<PkIndexEntry> pkEntriesToIndex,
            List<IndexedDbEntry> indexEntries) throws IOException {
        try (var out = new BufferedOutputStream(new FileOutputStream(file, true), Globals.BUFFER_SIZE)) {
            final var separator = separatorBytes(file);
            var currentOffset = file.length() + separator.length;
            out.write(separator);
            for (var entry : entries) {
                final var line = encodedLines.apply(entry);
                out.write(line);
                final var pkEntry = new PkIndexEntry(dbName, collName, entry.get_id(), currentOffset, line.length, page,
                        entry.getVersion());
                pkEntriesToIndex.add(pkEntry);
                indexEntries.add(indexed(dbName, collName, entry, pkEntry));
                currentOffset += line.length;
            }
        }
    }

    private static long appendedBytes(List<? extends DbEntry> entries) {
        var bytes = 0L;
        for (final var entry : entries) {
            bytes += entry.byteSize();
        }
        return bytes;
    }

    private static IndexedDbEntry indexed(String dbName, String collName, DbEntry entry, PkIndexEntry pkEntry) {
        final var indexedEntry = new IndexedDbEntry();
        indexedEntry.setIndex(pkEntry);
        indexedEntry.setCollectionName(collName);
        indexedEntry.setDatabaseName(dbName);
        indexedEntry.set_id(entry.get_id());
        indexedEntry.setData(entry.getData());
        indexedEntry.setVersion(entry.getVersion());
        return indexedEntry;
    }

    private static void rollBackBulkAppends(Map<File, Long> lengthsBeforeAppend) {
        for (final var appended : lengthsBeforeAppend.entrySet()) {
            final var lock = FileLocks.lockWrite(appended.getKey());
            try {
                FileLocks.truncateTo(appended.getKey(), appended.getValue());
            } finally {
                lock.unlock();
            }
        }
    }

    PkIndexEntry insert(DbEntry entry, byte[] line) throws IOException {
        final var dbName = entry.getDatabaseName();
        final var collName = entry.getCollectionName();
        final var page = entry.getPage();
        final var file = paths.collectionPage(dbName, collName, page);
        final var lock = FileLocks.lockWrite(file);
        try {
            vacuum.vacuumIfNeeded(dbName, collName, page, line.length, null, null);
            final var totalFileLength = file.length();
            final var separator = separatorBytes(file);
            try {
                appendToPage(file, separator, line);
                return pkIndexStore.indexNewPKValue(dbName, collName, entry.get_id(),
                        totalFileLength + separator.length, line.length, page, entry.getVersion());
            } catch (IOException e) {
                FileLocks.truncateTo(file, totalFileLength);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    private static byte[] separatorBytes(File file) throws IOException {
        return FileLocks.separatorBeforeAppend(file).getBytes(StandardCharsets.UTF_8);
    }

    private static void appendToPage(File file, byte[] separator, byte[] line) throws IOException {
        OwnerOnlyPermissions.ensureOwnerOnlyFile(file.toPath());
        try (var out = new FileOutputStream(file, true)) {
            if (separator.length > 0) {
                out.write(separator);
            }
            out.write(line);
        }
    }
}
