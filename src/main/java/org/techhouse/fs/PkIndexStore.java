package org.techhouse.fs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.log.Logger;

final class PkIndexStore {
    private static final Logger logger = Logger.logFor(PkIndexStore.class);

    private final FilePaths paths;

    PkIndexStore(FilePaths paths) {
        this.paths = paths;
    }

    void bulkIndexNewPKValues(String dbName, String collName, List<PkIndexEntry> pkEntries) throws IOException {
        appendEntries(paths.pkIndexFile(dbName, collName), pkEntries);
    }

    private void appendEntries(File indexFile, List<PkIndexEntry> pkEntries) throws IOException {
        final var lock = FileLocks.lockFor(indexFile).writeLock();
        lock.lock();
        try (var writer = new BufferedWriter(new FileWriter(indexFile, StandardCharsets.UTF_8, true),
                Globals.BUFFER_SIZE)) {
            for (var pkEntry : pkEntries) {
                writer.append(pkEntry.toFileEntry());
                writer.newLine();
            }
        } finally {
            lock.unlock();
        }
    }

    PkIndexEntry indexNewPKValue(String dbName, String collectionName, String value, long position, int length,
            long page, long version) throws IOException {
        final var indexEntry = new PkIndexEntry(dbName, collectionName, value, position, length, page, version);
        appendEntries(paths.pkIndexFile(dbName, collectionName), List.of(indexEntry));
        return indexEntry;
    }

    void deleteIndexValue(PkIndexEntry pkIndexEntry) throws IOException {
        internalUpdatePKIndex(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName(), pkIndexEntry.getValue(),
                null);
    }

    PkIndexEntry updateIndexValues(String dbName, String collectionName, String value, long position, int length,
            long page, long version) throws IOException {
        final var newIndexEntry = new PkIndexEntry(dbName, collectionName, value, position, length, page, version);
        internalUpdatePKIndex(dbName, collectionName, value, newIndexEntry);
        return newIndexEntry;
    }

    void internalUpdatePKIndex(String dbName, String collectionName, String value, PkIndexEntry newPkIndexEntry)
            throws IOException {
        final var indexFile = paths.pkIndexFile(dbName, collectionName);
        final var lock = FileLocks.lockFor(indexFile).writeLock();
        lock.lock();
        try {
            final List<String> existingLines = indexFile.exists()
                    ? FileLocks.decodeLines(Files.readAllBytes(indexFile.toPath()))
                    : List.of();
            final var others = new ArrayList<PkIndexEntry>(existingLines.size());
            PkIndexEntry oldEntry = null;
            for (final var line : existingLines) {
                if (line.isBlank()) {
                    continue;
                }
                final PkIndexEntry entry;
                try {
                    entry = PkIndexEntry.fromIndexFileEntry(dbName, collectionName, line);
                } catch (Exception e) {
                    logger.warning(
                            "Dropping malformed PK index entry in " + indexFile.getName() + ": " + e.getMessage());
                    continue;
                }
                if (entry.getValue().equals(value)) {
                    oldEntry = entry;
                } else {
                    others.add(entry);
                }
            }
            final var reIndexedEntries = reindexPks(oldEntry, newPkIndexEntry, others);
            final var lines = reIndexedEntries.stream().map(PkIndexEntry::toFileEntry).toList();
            FileLocks.rewriteFileAtomically(indexFile.toPath(), lines);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Only entries on the removed row's own page shift; a file-order shift would corrupt every other
     * page. {@code newPkIndexEntry} arrives carrying the page-file length as its position, so
     * subtracting the old row's length yields where the relocated row actually starts.
     */
    List<PkIndexEntry> reindexPks(PkIndexEntry oldEntry, PkIndexEntry newPkIndexEntry, List<PkIndexEntry> others) {
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
        others.sort(Comparator.comparing(PkIndexEntry::getValue));
        return others;
    }

    private record ParsedPkIndex(List<PkIndexEntry> entries, List<String> lines, boolean dropped,
            boolean unrecognised) {
    }

    List<PkIndexEntry> readWholePkIndexFile(String dbName, String collectionName) throws IOException {
        final var indexFile = paths.pkIndexFile(dbName, collectionName);
        if (!indexFile.exists()) {
            return new ArrayList<>();
        }
        final var readLock = FileLocks.lockFor(indexFile).readLock();
        readLock.lock();
        final ParsedPkIndex parsed;
        try {
            parsed = parsePkIndex(dbName, collectionName, indexFile);
        } finally {
            readLock.unlock();
        }
        if (parsed.unrecognised()) {
            logger.error("No line in " + indexFile.getName() + " could be read as a PK index entry, so it is not"
                    + " the file this loader expects; leaving it untouched. Nothing rebuilds the PK index, so it"
                    + " must never be rewritten from a read that understood none of it.");
            return parsed.entries();
        }
        if (!parsed.dropped()) {
            return parsed.entries();
        }
        final var writeLock = FileLocks.lockFor(indexFile).writeLock();
        writeLock.lock();
        try {
            final var reparsed = parsePkIndex(dbName, collectionName, indexFile);
            if (reparsed.dropped()) {
                FileLocks.rewriteFileAtomically(indexFile.toPath(), reparsed.lines());
            }
            return reparsed.entries();
        } finally {
            writeLock.unlock();
        }
    }

    private ParsedPkIndex parsePkIndex(String dbName, String collectionName, File indexFile) throws IOException {
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
        return new ParsedPkIndex(entries, new ArrayList<>(lineByValue.values()), dropped, dropped && entries.isEmpty());
    }

}
