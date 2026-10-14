package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileLocks.Parsed;
import org.techhouse.log.Logger;

final class PkIndexStore {
    private static final Logger logger = Logger.logFor(PkIndexStore.class);
    private static final String PK_INDEX_ROLLBACK_FAILURE = "Could not roll back a failed pk index append;"
            + " the pk index may list ids whose records are not on any page; REINDEX with \"primaryKey\": true"
            + " rebuilds it from the pages";
    private static final Parsed<List<PkIndexEntry>> EMPTY_PARSED = new Parsed<>(List.of(), List.of(), false, false);
    private static final KnownFile ABSENT = new KnownFile(0L, null, 0L);
    private static final Map<String, KnownFile> knownFiles = new ConcurrentHashMap<>();

    private final FilePaths paths;

    private record KnownFile(long length, FileTime modified, long digest) {
    }

    PkIndexStore(FilePaths paths) {
        this.paths = paths;
    }

    void bulkIndexNewPKValues(String dbName, String collName, List<PkIndexEntry> pkEntries) throws IOException {
        final var indexFile = paths.pkIndexFile(dbName, collName);
        final var lock = FileLocks.lockWrite(indexFile);
        try {
            final var before = knownMatching(indexFile);
            forget(indexFile);
            FileLocks.appendLines(indexFile, pkEntries, PkIndexEntry::toFileEntry, PK_INDEX_ROLLBACK_FAILURE);
            if (before != null) {
                remember(indexFile, before.digest() + PkRowDigest.sum(pkEntries));
            }
        } finally {
            lock.unlock();
        }
    }

    PkIndexEntry indexNewPKValue(String dbName, String collectionName, String value, long position, int length,
            long page, long version) throws IOException {
        final var indexEntry = new PkIndexEntry(dbName, collectionName, value, position, length, page, version);
        bulkIndexNewPKValues(dbName, collectionName, List.of(indexEntry));
        return indexEntry;
    }

    void deleteIndexValue(PkIndexEntry pkIndexEntry) throws IOException {
        deleteIndexValue(pkIndexEntry, null);
    }

    void deleteIndexValue(PkIndexEntry pkIndexEntry, List<PkIndexEntry> residentRows) throws IOException {
        rewriteFrom(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName(), residentRows,
                PkChange.delete(pkIndexEntry.getValue()));
    }

    PkIndexEntry updateIndexValues(String dbName, String collectionName, String value, long position, int length,
            long page, long version, List<PkIndexEntry> residentRows) throws IOException {
        final var newIndexEntry = new PkIndexEntry(dbName, collectionName, value, position, length, page, version);
        rewriteFrom(dbName, collectionName, residentRows, PkChange.update(newIndexEntry));
        return newIndexEntry;
    }

    void rewriteFrom(String dbName, String collectionName, List<PkIndexEntry> residentRows, PkChange change)
            throws IOException {
        final var indexFile = paths.pkIndexFile(dbName, collectionName);
        final var lock = FileLocks.lockWrite(indexFile);
        try {
            final var located = locateResident(indexFile, residentRows, change.removedId());
            if (located == null) {
                internalUpdatePKIndex(dbName, collectionName, change.removedId(), change.replacement());
            } else {
                writeFrom(indexFile, located, change.replacement());
            }
        } finally {
            lock.unlock();
        }
    }

    private static PkRowDigest.Located locateResident(File indexFile, List<PkIndexEntry> residentRows,
            String removedId) {
        if (residentRows == null || !indexFile.exists()) {
            return null;
        }
        final var known = knownMatching(indexFile);
        return known == null ? null : PkRowDigest.locate(residentRows, removedId, known.digest());
    }

    private static void writeFrom(File indexFile, PkRowDigest.Located located, PkIndexEntry replacement)
            throws IOException {
        final var removed = located.removed();
        if (replacement != null) {
            replacement.setPosition(replacement.getPosition() - removed.getLength());
        }
        final var digest = new long[1];
        forget(indexFile);
        FileLocks.rewriteFileAtomically(indexFile.toPath(), out -> {
            final var lines = new PkLineWriter(out);
            for (final var row : located.rows()) {
                if (row == removed) {
                    if (replacement != null) {
                        digest[0] += lines.write(replacement);
                    }
                } else if (row.getPage() == removed.getPage() && row.getPosition() > removed.getPosition()) {
                    digest[0] += lines.write(row, row.getPosition() - removed.getLength());
                } else {
                    digest[0] += lines.write(row);
                }
            }
        });
        remember(indexFile, digest[0]);
    }

    private static void writeRows(File indexFile, List<PkIndexEntry> rows) throws IOException {
        forget(indexFile);
        FileLocks.rewriteFileAtomically(indexFile.toPath(), out -> {
            final var lines = new PkLineWriter(out);
            for (final var row : rows) {
                lines.write(row);
            }
        });
        remember(indexFile, PkRowDigest.sum(rows));
    }

    private static KnownFile knownMatching(File indexFile) {
        if (!indexFile.exists()) {
            return ABSENT;
        }
        final var known = knownFiles.get(indexFile.getAbsolutePath());
        if (known == null || known.modified() == null) {
            return null;
        }
        try {
            final var attributes = Files.readAttributes(indexFile.toPath(), BasicFileAttributes.class);
            return attributes.size() == known.length() && attributes.lastModifiedTime().equals(known.modified())
                    ? known
                    : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void remember(File indexFile, long digest) {
        try {
            final var attributes = Files.readAttributes(indexFile.toPath(), BasicFileAttributes.class);
            knownFiles.put(indexFile.getAbsolutePath(),
                    new KnownFile(attributes.size(), attributes.lastModifiedTime(), digest));
        } catch (IOException e) {
            forget(indexFile);
        }
    }

    private static void forget(File indexFile) {
        knownFiles.remove(indexFile.getAbsolutePath());
    }

    void internalUpdatePKIndex(String dbName, String collectionName, String value, PkIndexEntry newPkIndexEntry)
            throws IOException {
        if (!rewriteRows(dbName, collectionName, rows -> reindexPks(value, newPkIndexEntry, rows))) {
            throw new IOException("No line in " + paths.pkIndexFile(dbName, collectionName).getName()
                    + " could be read as a PK index entry, so it is not the file this write expects; refusing to"
                    + " rewrite it");
        }
    }

    boolean rewriteRows(String dbName, String collectionName, UnaryOperator<List<PkIndexEntry>> change)
            throws IOException {
        final var indexFile = paths.pkIndexFile(dbName, collectionName);
        final var lock = FileLocks.lockWrite(indexFile);
        try {
            final var parsed = indexFile.exists() ? parsePkIndex(dbName, collectionName, indexFile) : EMPTY_PARSED;
            if (parsed.unrecognised()) {
                return false;
            }
            final var changed = change.apply(new ArrayList<>(parsed.entries()));
            changed.sort(Comparator.comparing(PkIndexEntry::getValue));
            writeRows(indexFile, changed);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Only entries on the removed row's own page shift; a file-order shift would corrupt every other
     * page. {@code newPkIndexEntry} arrives carrying the page-file length as its position, so
     * subtracting the old row's length yields where the relocated row actually starts.
     */
    private List<PkIndexEntry> reindexPks(String value, PkIndexEntry newPkIndexEntry, List<PkIndexEntry> rows) {
        PkIndexEntry oldEntry = null;
        final var others = new ArrayList<PkIndexEntry>(rows.size());
        for (final var entry : rows) {
            if (entry.getValue().equals(value)) {
                oldEntry = entry;
            } else {
                others.add(entry);
            }
        }
        if (oldEntry != null) {
            for (final var entry : others) {
                if (entry.getPage() == oldEntry.getPage() && entry.getPosition() > oldEntry.getPosition()) {
                    entry.setPosition(entry.getPosition() - oldEntry.getLength());
                }
            }
        }
        if (newPkIndexEntry != null) {
            if (oldEntry != null) {
                newPkIndexEntry.setPosition(newPkIndexEntry.getPosition() - oldEntry.getLength());
            }
            others.add(newPkIndexEntry);
        }
        return others;
    }

    void replaceAll(String dbName, String collectionName, List<PkIndexEntry> entries) throws IOException {
        final var indexFile = paths.pkIndexFile(dbName, collectionName);
        final var lock = FileLocks.lockWrite(indexFile);
        try {
            final var sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparing(PkIndexEntry::getValue));
            writeRows(indexFile, sorted);
        } finally {
            lock.unlock();
        }
    }

    List<PkIndexEntry> readWholePkIndexFile(String dbName, String collectionName) throws IOException {
        final var parsed = readHealed(dbName, collectionName);
        if (parsed.unrecognised()) {
            throw new IOException("No line in " + paths.pkIndexFile(dbName, collectionName).getName()
                    + " could be read as a PK index entry; it is left untouched. Rebuild it with REINDEX and"
                    + " \"primaryKey\": true, or repair or restore it, before reading or writing " + dbName + "|"
                    + collectionName);
        }
        return parsed.entries();
    }

    Optional<List<PkIndexEntry>> readRecognisedPkIndex(String dbName, String collectionName) throws IOException {
        final var parsed = readHealed(dbName, collectionName);
        return parsed.unrecognised() ? Optional.empty() : Optional.of(parsed.entries());
    }

    private Parsed<List<PkIndexEntry>> readHealed(String dbName, String collectionName) throws IOException {
        final var indexFile = paths.pkIndexFile(dbName, collectionName);
        if (!indexFile.exists()) {
            return new Parsed<>(new ArrayList<>(), List.of(), false, false);
        }
        final var parsed = FileLocks.readHealing(indexFile, file -> parsePkIndex(dbName, collectionName, file));
        if (parsed.unrecognised()) {
            logger.error("No line in " + indexFile.getName() + " could be read as a PK index entry, so it is not"
                    + " the file this loader expects; leaving it untouched, because it must never be rewritten from a"
                    + " read that understood none of it. REINDEX with \"primaryKey\": true rebuilds it from the pages.");
        }
        return parsed;
    }

    private Parsed<List<PkIndexEntry>> parsePkIndex(String dbName, String collectionName, File indexFile)
            throws IOException {
        final var byValue = new LinkedHashMap<String, PkIndexEntry>();
        final var lineByValue = new LinkedHashMap<String, String>();
        boolean dropped = false;
        for (var line : FileLocks.decodeLines(Files.readAllBytes(indexFile.toPath()))) {
            if (line.isEmpty())
                continue;
            try {
                final var entry = PkIndexEntry.fromIndexFileEntry(dbName, collectionName, line);
                if (byValue.put(entry.getValue(), entry) != null) {
                    dropped = true;
                    logger.warning("Removing duplicate PK index entry for '" + entry.getValue() + "' in "
                            + indexFile.getName() + ", keeping the last occurrence");
                }
                lineByValue.put(entry.getValue(), line);
            } catch (Exception e) {
                dropped = true;
                logger.warning("Removing malformed PK index entry in " + indexFile.getName() + ": " + e.getMessage());
            }
        }
        final var entries = new ArrayList<>(byValue.values());
        entries.sort(Comparator.comparing(PkIndexEntry::getValue));
        if (dropped) {
            forget(indexFile);
        } else {
            remember(indexFile, PkRowDigest.sum(entries));
        }
        return new Parsed<>(entries, new ArrayList<>(lineByValue.values()), dropped, dropped && entries.isEmpty());
    }

}
