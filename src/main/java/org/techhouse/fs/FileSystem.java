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
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ex.DirectoryNotFoundException;

public class FileSystem {
    private final FilePaths paths = new FilePaths();
    private final FieldIndexStore fieldIndexStore = new FieldIndexStore(paths);
    private final FieldIndexLoader fieldIndexLoader = new FieldIndexLoader(paths);
    private final DocumentPageStore documentPageStore = new DocumentPageStore(paths);
    private final PkIndexStore pkIndexStore = new PkIndexStore(paths);

    public void createBaseDbPath() {
        paths.useDbPath(Configuration.getInstance().getFilePath());
        final var directory = new File(paths.dbPath());
        if (!directory.exists()) {
            var result = directory.mkdir();
            if (!result) {
                throw new DirectoryNotFoundException(directory.getAbsolutePath());
            }
        }
    }

    public void createAdminDatabase() throws IOException {
        createDatabaseFolder(Globals.ADMIN_DB_NAME);
        createAdminPagesFolder();
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME);
        final var pagesDatabases = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME,
                Globals.ADMIN_DATABASES_COLLECTION_NAME);
        final var pagesCollections = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME,
                Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        final var pagesUsers = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME,
                Globals.ADMIN_USERS_COLLECTION_NAME);
        final var pagesCollectionUsage = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME,
                Globals.ADMIN_COLLECTION_USAGE_NAME);
        final var pagesTransactions = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME,
                Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        final var pagesTriggerRuns = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME,
                Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME);
        createCollectionFile(Globals.ADMIN_PAGES_DB_NAME, pagesDatabases);
        createCollectionFile(Globals.ADMIN_PAGES_DB_NAME, pagesCollections);
        createCollectionFile(Globals.ADMIN_PAGES_DB_NAME, pagesUsers);
        createCollectionFile(Globals.ADMIN_PAGES_DB_NAME, pagesCollectionUsage);
        createCollectionFile(Globals.ADMIN_PAGES_DB_NAME, pagesTransactions);
        createCollectionFile(Globals.ADMIN_PAGES_DB_NAME, pagesTriggerRuns);
    }

    // Create the nested admin/pages parent up front (per-collection folders below are mkdir'd one level deep).
    private void createAdminPagesFolder() {
        final var pagesFolder = paths.adminPagesFolder();
        if (!pagesFolder.exists() && !pagesFolder.mkdirs()) {
            throw new DirectoryNotFoundException(pagesFolder.getAbsolutePath());
        }
    }

    public boolean createDatabaseFolder(String dbName) {
        final var dbFolder = paths.rawDatabaseFolder(dbName);
        if (!dbFolder.exists()) {
            return dbFolder.mkdir();
        }
        return true;
    }

    public boolean deleteDatabase(String dbName) {
        final var dbFolder = paths.rawDatabaseFolder(dbName);
        final var fileDeletionResult = new ArrayList<Boolean>();
        if (dbFolder.exists()) {
            final var dbFolders = dbFolder.listFiles();
            if (dbFolders != null) {
                for (var collFolder : dbFolders) {
                    final var collFiles = collFolder.listFiles();
                    if (collFiles != null) {
                        for (var file : collFiles) {
                            fileDeletionResult.add(file.delete());
                        }
                        fileDeletionResult.add(collFolder.delete());
                    }
                }
            }
            fileDeletionResult.add(dbFolder.delete());
            return fileDeletionResult.stream().allMatch(aBoolean -> aBoolean);
        }
        return false;
    }

    public boolean createCollectionFile(String dbName, String collectionName) throws IOException {
        final var collectionFile = paths.collectionPage(dbName, collectionName, 0);
        final var collectionFolder = new File(collectionFile.getParent());
        if (!collectionFolder.exists()) {
            if (collectionFolder.mkdir()) {
                return collectionFile.createNewFile();
            } else {
                return false;
            }
        } else {
            if (!collectionFile.exists()) {
                return collectionFile.createNewFile();
            }
            return true;
        }
    }

    public boolean deleteCollectionFiles(String dbName, String collectionName) {
        final var collectionFile = paths.collectionPage(dbName, collectionName, 0);
        final var collectionFolder = new File(collectionFile.getParent());
        final var fileDeletionResult = new ArrayList<Boolean>();
        if (collectionFolder.exists()) {
            for (var file : Objects.requireNonNull(collectionFolder.listFiles())) {
                fileDeletionResult.add(file.delete());
            }
            fileDeletionResult.add(collectionFolder.delete());
            return fileDeletionResult.stream().allMatch(aBoolean -> aBoolean);
        }
        return false;
    }

    public void writeCollectionSchema(String dbName, String collName, String schemaJson) throws IOException {
        MetadataFileStore.write(paths.schemaFile(dbName, collName), schemaJson);
    }

    public String readCollectionSchema(String dbName, String collName) throws IOException {
        return MetadataFileStore.read(paths.schemaFile(dbName, collName));
    }

    public boolean deleteCollectionSchema(String dbName, String collName) {
        return MetadataFileStore.delete(paths.schemaFile(dbName, collName));
    }

    public void writeProcedure(String dbName, String name, String json) throws IOException {
        MetadataFileStore.ensureFolder(paths.proceduresFolder(dbName), "procedures", dbName);
        MetadataFileStore.write(paths.procedureFile(dbName, name), json);
    }

    public String readProcedure(String dbName, String name) throws IOException {
        return MetadataFileStore.read(paths.procedureFile(dbName, name));
    }

    public boolean deleteProcedure(String dbName, String name) {
        return MetadataFileStore.delete(paths.procedureFile(dbName, name));
    }

    public List<String> listProcedureNames(String dbName) {
        return MetadataFileStore.listNames(paths.proceduresFolder(dbName), Globals.PROCEDURE_FILE_EXTENSION);
    }

    public void writeSchedule(String dbName, String name, String json) throws IOException {
        MetadataFileStore.ensureFolder(paths.schedulesFolder(dbName), "schedules", dbName);
        MetadataFileStore.write(paths.scheduleFile(dbName, name), json);
    }

    public String readSchedule(String dbName, String name) throws IOException {
        return MetadataFileStore.read(paths.scheduleFile(dbName, name));
    }

    public boolean deleteSchedule(String dbName, String name) {
        return MetadataFileStore.delete(paths.scheduleFile(dbName, name));
    }

    public List<String> listScheduleNames(String dbName) {
        return MetadataFileStore.listNames(paths.schedulesFolder(dbName), Globals.SCHEDULE_FILE_EXTENSION);
    }

    public void writeTriggers(String dbName, String collName, String json) throws IOException {
        MetadataFileStore.write(paths.triggersFile(dbName, collName), json);
    }

    public String readTriggers(String dbName, String collName) throws IOException {
        return MetadataFileStore.read(paths.triggersFile(dbName, collName));
    }

    public boolean deleteTriggers(String dbName, String collName) {
        return MetadataFileStore.delete(paths.triggersFile(dbName, collName));
    }

    public void appendTombstone(String dbName, String collName, String id, long version) throws IOException {
        TombstoneStore.append(paths.tombstoneFile(dbName, collName), id, version);
    }

    public Map<String, Long> readTombstones(String dbName, String collName) throws IOException {
        return TombstoneStore.read(paths.tombstoneFile(dbName, collName));
    }

    public void compactTombstones(String dbName, String collName, long minVersionToKeep) throws IOException {
        TombstoneStore.compact(paths.tombstoneFile(dbName, collName), minVersionToKeep);
    }

    public List<PkIndexEntry> readWholePkIndexFile(String dbName, String collectionName) throws IOException {
        return pkIndexStore.readWholePkIndexFile(dbName, collectionName);
    }

    public PkIndexEntry findPkIndexEntry(String dbName, String collName, String id) throws IOException {
        return pkIndexStore.findPkIndexEntry(dbName, collName, id);
    }

    public DbEntry getById(PkIndexEntry pkIndexEntry) throws Exception {
        return documentPageStore.getById(pkIndexEntry);
    }

    public List<DbEntry> getByIndexEntries(List<PkIndexEntry> entries) throws IOException {
        return documentPageStore.getByIndexEntries(entries);
    }

    public <T extends DbEntry> List<IndexedDbEntry> bulkInsertIntoCollection(final String dbName, final String collName,
            final List<T> entries) throws IOException {
        final var indexEntries = new ArrayList<IndexedDbEntry>();
        final var pkEntriesToIndex = new ArrayList<PkIndexEntry>();
        final var entrySet = entries.stream().collect(Collectors.groupingBy(DbEntry::getPage)).entrySet();
        for (var groupedEntry : entrySet) {
            final var page = groupedEntry.getKey();
            final var pageEntries = groupedEntry.getValue();
            final var file = paths.collectionPage(dbName, collName, page);
            final var lock = FileLocks.lockFor(file).writeLock();
            lock.lock();
            try (var writer = new BufferedWriter(new FileWriter(file, true), Globals.BUFFER_SIZE)) {
                var currentOffset = file.length();
                for (var entry : pageEntries) {
                    final var strData = entry.toFileEntry() + Globals.NEWLINE;
                    final var bytes = strData.getBytes(StandardCharsets.UTF_8);
                    final var length = bytes.length;
                    writer.append(strData);
                    final var pkEntry = new PkIndexEntry(dbName, collName, entry.get_id(), currentOffset, length, page,
                            entry.getVersion());
                    pkEntriesToIndex.add(pkEntry);
                    final var indexedEntry = new IndexedDbEntry();
                    indexedEntry.setIndex(pkEntry);
                    indexedEntry.setCollectionName(collName);
                    indexedEntry.setDatabaseName(dbName);
                    indexedEntry.set_id(entry.get_id());
                    indexedEntry.setData(entry.getData());
                    indexEntries.add(indexedEntry);
                    currentOffset += length;
                }
            } finally {
                lock.unlock();
            }
        }
        pkIndexStore.bulkIndexNewPKValues(dbName, collName, pkEntriesToIndex);
        return indexEntries;
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry) throws IOException {
        final var dbName = entry.getDatabaseName();
        final var collName = entry.getCollectionName();
        final var page = entry.getPage();
        final var file = paths.collectionPage(dbName, collName, page);
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        try (var writer = new BufferedWriter(new FileWriter(file, true), Globals.BUFFER_SIZE)) {
            final var strData = entry.toFileEntry() + Globals.NEWLINE;
            final var bytes = strData.getBytes(StandardCharsets.UTF_8);
            final var length = bytes.length;
            var totalFileLength = file.length();
            writer.append(strData);
            final var entryId = entry.get_id();
            return pkIndexStore.indexNewPKValue(entry.getDatabaseName(), entry.getCollectionName(), entryId,
                    totalFileLength, length, page, entry.getVersion());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Deletes the entry from its page, compacting the survivors. Returns the {@link PkCompaction}
     * describing the shift (so the caller can fix the in-memory PK positions via
     * {@code Cache.shiftPkPositionsAfterCompaction}), or {@code null} when no survivor moved.
     */
    public PkCompaction deleteFromCollection(PkIndexEntry pkIndexEntry) {
        final var dbName = pkIndexEntry.getDatabaseName();
        final var collName = pkIndexEntry.getCollectionName();
        final var page = pkIndexEntry.getPage();
        final var file = paths.collectionPage(dbName, collName, page);
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        try (var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            final long totalFileLength = file.length();
            final var compacted = shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
            writer.setLength(totalFileLength - pkIndexEntry.getLength());
            pkIndexStore.deleteIndexValue(pkIndexEntry);
            return compacted ? compactionFor(pkIndexEntry) : null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
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

    /**
     * Shifts the entries after {@code pkIndexEntry} toward the start of the page, overwriting its
     * slot. Returns {@code true} when entries were actually moved (so the caller must fix the
     * in-memory PK positions of the survivors), {@code false} when there was nothing to shift — the
     * removed entry was the last one, or its position is already past the end of the file (a stale
     * position from a concurrent compaction/drop), in which case allocating the buffer is skipped.
     */
    private boolean shiftOtherEntriesToStart(RandomAccessFile writer, PkIndexEntry pkIndexEntry, long totalFileLength)
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

    /**
     * Updates many entries by delegating to the single-entry {@link #updateFromCollection}, which keeps
     * the page file and PK index file correct for each row. Earlier updates shift the file positions of
     * later same-page entries, so this drives each {@code updateFromCollection} with a private,
     * progressively-adjusted copy of the target's index entry (never mutating the caller's cached
     * {@link PkIndexEntry} objects). The returned {@link BulkUpdateResult} carries the new entries plus
     * the ordered compactions the caller must apply to fix the in-memory positions of the surviving
     * (non-updated) entries.
     */
    public BulkUpdateResult bulkUpdateFromCollection(String dbName, String collName, List<IndexedDbEntry> entries)
            throws IOException {
        final var updated = new ArrayList<IndexedDbEntry>();
        final var compactions = new ArrayList<PkCompaction>();
        // Private copies of the target index entries; their positions are adjusted as earlier updates
        // compact the page, so each delegated update sees the current on-disk position.
        final var working = new ArrayList<PkIndexEntry>(entries.size());
        for (final var entry : entries) {
            final var idx = entry.getIndex();
            working.add(new PkIndexEntry(idx.getDatabaseName(), idx.getCollectionName(), idx.getValue(),
                    idx.getPosition(), idx.getLength(), idx.getPage(), idx.getVersion()));
        }
        for (int i = 0; i < entries.size(); i++) {
            final var entry = entries.get(i);
            final var target = working.get(i);
            final var result = updateFromCollection(entry.toDbEntry(), target);
            final var updatedIndexEntry = new IndexedDbEntry();
            updatedIndexEntry.setIndex(result.indexEntry());
            updatedIndexEntry.set_id(entry.get_id());
            updatedIndexEntry.setCollectionName(collName);
            updatedIndexEntry.setDatabaseName(dbName);
            updatedIndexEntry.setData(entry.getData());
            updatedIndexEntry.setPreviousByteSize(target.getLength());
            updated.add(updatedIndexEntry);
            final var compaction = result.compaction();
            if (compaction != null) {
                compactions.add(compaction);
                // This update relocated the row to the end and shifted later same-page entries toward
                // the start. Apply the same shift to the still-to-be-processed working copies (so the
                // next update sees the current on-disk position) and to the already-relocated entries
                // from earlier iterations (so their reported new positions stay correct), but not to
                // the row we just relocated.
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

    /**
     * Updates the entry in place, relocating it to the end of its page and compacting the survivors.
     * Returns the new {@link PkIndexEntry} together with the {@link PkCompaction} the caller must
     * apply to the in-memory PK positions (or a null compaction when no survivor moved).
     */
    public UpdateResult updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry) throws IOException {
        final var dbName = entry.getDatabaseName();
        final var collName = entry.getCollectionName();
        final var page = entry.getPage();
        final var file = paths.collectionPage(dbName, collName, page);
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        try (var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            final long totalFileLength = file.length();
            final var compacted = shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
            writer.seek(totalFileLength - pkIndexEntry.getLength());
            final var strData = entry.toFileEntry() + Globals.NEWLINE;
            final var bytes = strData.getBytes(StandardCharsets.UTF_8);
            final var length = bytes.length;
            writer.write(bytes, 0, length);
            writer.setLength(totalFileLength - pkIndexEntry.getLength() + length);
            entry.setPreviousByteSize(pkIndexEntry.getLength());
            final var updated = pkIndexStore.updateIndexValues(entry.getDatabaseName(), entry.getCollectionName(),
                    entry.get_id(), totalFileLength, length, page, entry.getVersion());
            return new UpdateResult(updated, compacted ? compactionFor(pkIndexEntry) : null);
        } finally {
            lock.unlock();
        }
    }

    public void writeIndexFile(String dbName, String collName, String fieldName,
            Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap) {
        fieldIndexStore.writeIndexFile(dbName, collName, fieldName, indexEntryMap);
    }

    public void writeHashIndexFile(String dbName, String collName, String fieldName, IndexKind kind,
            List<FieldIndexEntry<String>> entries) {
        fieldIndexStore.writeHashIndexFile(dbName, collName, fieldName, kind, entries);
    }

    public void updateHashIndexFiles(String dbName, String collName, String fieldName, IndexKind kind,
            FieldIndexEntry<String> insertedEntry, FieldIndexEntry<String> removedEntry) throws IOException {
        fieldIndexStore.updateHashIndexFiles(dbName, collName, fieldName, kind, insertedEntry, removedEntry);
    }

    public <T, K> void updateIndexFiles(String dbName, String collName, String fieldName,
            FieldIndexEntry<T> insertedEntry, FieldIndexEntry<K> removedEntry) throws IOException {
        fieldIndexStore.updateIndexFiles(dbName, collName, fieldName, insertedEntry, removedEntry);
    }

    public boolean dropIndex(String dbName, String collName, String fieldName) {
        return fieldIndexStore.dropIndex(dbName, collName, fieldName);
    }

    public ConcurrentMap<String, List<FieldIndexEntry<?>>> readAllWholeFieldIndexFiles(String dbName, String collName,
            String fieldName) {
        return fieldIndexLoader.readAllWholeFieldIndexFiles(dbName, collName, fieldName);
    }

    public <T> List<FieldIndexEntry<T>> readWholeFieldIndexFiles(String dbName, String collName, String fieldName,
            Class<T> indexType) throws IOException {
        return fieldIndexLoader.readWholeFieldIndexFiles(dbName, collName, fieldName, indexType);
    }

    public List<FieldIndexEntry<String>> readWholeHashIndexFile(String dbName, String collName, String fieldName,
            IndexKind kind) throws IOException {
        return fieldIndexLoader.readWholeHashIndexFile(dbName, collName, fieldName, kind);
    }

    public Map<String, DbEntry> readWholeCollectionPage(String dbName, String collectionName, long page)
            throws IOException {
        return documentPageStore.readWholeCollectionPage(dbName, collectionName, page);
    }

    public Stream<Map<String, DbEntry>> streamPages(String dbName, String collName) throws IOException {
        return documentPageStore.streamPages(dbName, collName);
    }

    public Stream<DbEntry> streamEntries(String dbName, String collName) throws IOException {
        return documentPageStore.streamEntries(dbName, collName);
    }

}
