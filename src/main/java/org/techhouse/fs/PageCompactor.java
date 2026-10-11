package org.techhouse.fs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ex.PartialBulkUpdateException;
import org.techhouse.fs.CompactionJournal.Kind;
import org.techhouse.fs.CompactionJournal.Marker;
import org.techhouse.log.Logger;

final class PageCompactor {
    private static final Logger logger = Logger.logFor(PageCompactor.class);

    private final FilePaths paths;
    private final PkIndexStore pkIndexStore;
    private final CompactionJournal journal;
    private final Map<String, Marker> openRelocations = new ConcurrentHashMap<>();

    PageCompactor(FilePaths paths, PkIndexStore pkIndexStore, CompactionJournal journal) {
        this.paths = paths;
        this.pkIndexStore = pkIndexStore;
        this.journal = journal;
    }

    PkCompaction delete(PkIndexEntry pkIndexEntry) {
        try {
            final var compacted = removeFromPage(Kind.DELETE, pkIndexEntry);
            journal.end(compacted.marker());
            return compacted.compaction();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    PkCompaction deleteForRelocation(PkIndexEntry source) {
        try {
            final var compacted = removeFromPage(Kind.RELOCATE, source);
            openRelocations.put(relocationKey(source), compacted.marker());
            return compacted.compaction();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void retargetRelocation(PkIndexEntry source, long targetPage) throws IOException {
        final var key = relocationKey(source);
        final var marker = openRelocations.get(key);
        if (marker == null) {
            return;
        }
        final var target = paths.collectionPage(source.getDatabaseName(), source.getCollectionName(), targetPage);
        openRelocations.put(key, journal.retarget(marker, targetPage, target.exists() ? target.length() : 0L));
    }

    void endRelocation(PkIndexEntry source) {
        final var marker = openRelocations.remove(relocationKey(source));
        if (marker != null) {
            journal.end(marker);
        }
    }

    private static String relocationKey(PkIndexEntry source) {
        return source.getDatabaseName() + Globals.COLL_IDENTIFIER_SEPARATOR + source.getCollectionName()
                + Globals.COLL_IDENTIFIER_SEPARATOR + source.getValue();
    }

    private record Compacted(Marker marker, PkCompaction compaction) {
    }

    private Compacted removeFromPage(Kind kind, PkIndexEntry pkIndexEntry) throws IOException {
        final var file = paths.collectionPage(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName(),
                pkIndexEntry.getPage());
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        try (var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            final long totalFileLength = file.length();
            requireWithinPage(pkIndexEntry, totalFileLength);
            final var tail = readRegion(writer, pkIndexEntry.getPosition(), totalFileLength);
            final var marker = journal.begin(kind, pkIndexEntry, totalFileLength, tail);
            try {
                final var compacted = shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
                writer.setLength(totalFileLength - pkIndexEntry.getLength());
                pkIndexStore.deleteIndexValue(pkIndexEntry);
                return new Compacted(marker, compacted ? compactionFor(pkIndexEntry) : null);
            } catch (IOException e) {
                restoreAndEnd(writer, marker, pkIndexEntry, tail, totalFileLength);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    private void restoreAndEnd(RandomAccessFile writer, Marker marker, PkIndexEntry pkIndexEntry, byte[] tail,
            long totalFileLength) {
        restoreRegion(writer, pkIndexEntry.getPosition(), tail, totalFileLength);
        journal.end(marker);
    }

    private static PkCompaction compactionFor(PkIndexEntry pkIndexEntry) {
        return new PkCompaction(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName(),
                pkIndexEntry.getPage(), pkIndexEntry.getPosition(), pkIndexEntry.getLength());
    }

    private static void shiftIfAfter(PkIndexEntry entry, PkCompaction compaction) {
        if (entry.getPage() == compaction.page() && entry.getPosition() > compaction.removedPosition()) {
            entry.setPosition(entry.getPosition() - compaction.removedLength());
        }
    }

    BulkUpdateResult bulkUpdate(String dbName, String collName, List<IndexedDbEntry> entries) throws IOException {
        final var updated = new ArrayList<IndexedDbEntry>();
        final var compactions = new ArrayList<PkCompaction>();
        final var working = new ArrayList<PkIndexEntry>(entries.size());
        for (final var entry : entries) {
            final var idx = entry.getIndex();
            working.add(new PkIndexEntry(idx.getDatabaseName(), idx.getCollectionName(), idx.getValue(),
                    idx.getPosition(), idx.getLength(), idx.getPage(), idx.getVersion()));
        }
        for (int i = 0; i < entries.size(); i++) {
            final var entry = entries.get(i);
            final var target = working.get(i);
            final UpdateResult result;
            try {
                result = update(entry.toDbEntry(), target);
            } catch (IOException e) {
                throw new PartialBulkUpdateException(new BulkUpdateResult(updated, compactions), e);
            }
            final var updatedIndexEntry = new IndexedDbEntry();
            updatedIndexEntry.setIndex(result.indexEntry());
            updatedIndexEntry.set_id(entry.get_id());
            updatedIndexEntry.setCollectionName(collName);
            updatedIndexEntry.setDatabaseName(dbName);
            updatedIndexEntry.setData(entry.getData());
            updatedIndexEntry.setVersion(result.indexEntry().getVersion());
            updatedIndexEntry.setPreviousByteSize(target.getLength());
            updated.add(updatedIndexEntry);
            final var compaction = result.compaction();
            if (compaction != null) {
                compactions.add(compaction);
                for (int j = i + 1; j < working.size(); j++) {
                    shiftIfAfter(working.get(j), compaction);
                }
                for (int k = 0; k < i; k++) {
                    shiftIfAfter(updated.get(k).getIndex(), compaction);
                }
            }
        }
        return new BulkUpdateResult(updated, compactions);
    }

    UpdateResult update(DbEntry entry, PkIndexEntry pkIndexEntry) throws IOException {
        final var page = entry.getPage();
        final var file = paths.collectionPage(entry.getDatabaseName(), entry.getCollectionName(), page);
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        try (var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            final long totalFileLength = file.length();
            requireWithinPage(pkIndexEntry, totalFileLength);
            final var tail = readRegion(writer, pkIndexEntry.getPosition(), totalFileLength);
            final var marker = journal.begin(Kind.UPDATE, pkIndexEntry, totalFileLength, tail);
            try {
                final var compacted = shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
                writer.seek(totalFileLength - pkIndexEntry.getLength());
                final var bytes = (entry.toFileEntry() + Globals.NEWLINE).getBytes(StandardCharsets.UTF_8);
                final var length = bytes.length;
                writer.write(bytes, 0, length);
                writer.setLength(totalFileLength - pkIndexEntry.getLength() + length);
                entry.setPreviousByteSize(pkIndexEntry.getLength());
                final var updated = pkIndexStore.updateIndexValues(entry.getDatabaseName(), entry.getCollectionName(),
                        entry.get_id(), totalFileLength, length, page, entry.getVersion());
                journal.end(marker);
                return new UpdateResult(updated, compacted ? compactionFor(pkIndexEntry) : null);
            } catch (IOException e) {
                restoreAndEnd(writer, marker, pkIndexEntry, tail, totalFileLength);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    static void requireWithinPage(PkIndexEntry entry, long pageLength) throws IOException {
        if (entry.getPosition() + entry.getLength() > pageLength) {
            throw new IOException("The PK index places '" + entry.getValue() + "' at " + entry.getPosition() + "+"
                    + entry.getLength() + " on page " + entry.getPage() + ", past its " + pageLength
                    + "-byte end; refusing to shift the page around a record that is not there");
        }
    }

    static byte[] readRegion(RandomAccessFile writer, long from, long to) throws IOException {
        final var length = (int) (to - from);
        if (length <= 0) {
            return new byte[0];
        }
        final var buffer = new byte[length];
        writer.seek(from);
        writer.readFully(buffer, 0, length);
        return buffer;
    }

    static void restoreRegion(RandomAccessFile writer, long from, byte[] region, long originalLength) {
        try {
            if (region.length > 0) {
                writer.seek(from);
                writer.write(region, 0, region.length);
            }
            writer.setLength(originalLength);
        } catch (IOException restoreFailure) {
            logger.error("Could not restore the page after a failed page or index write; run REINDEX on this"
                    + " collection", restoreFailure);
        }
    }

    static boolean shiftOtherEntriesToStart(RandomAccessFile writer, PkIndexEntry pkIndexEntry, long totalFileLength)
            throws IOException {
        final int otherEntriesLength = (int) (totalFileLength - pkIndexEntry.getPosition() - pkIndexEntry.getLength());
        if (otherEntriesLength <= 0) {
            return false;
        }
        writer.seek(pkIndexEntry.getPosition() + pkIndexEntry.getLength());
        byte[] buffer = new byte[otherEntriesLength];
        writer.readFully(buffer, 0, otherEntriesLength);
        writer.seek(pkIndexEntry.getPosition());
        writer.write(buffer, 0, otherEntriesLength);
        return true;
    }
}
