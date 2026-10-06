package org.techhouse.fs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
    private final DirtyIndexMarkers dirtyIndexMarkers = new DirtyIndexMarkers(paths);
    private final IndexBuildMarkers indexBuildMarkers = new IndexBuildMarkers(paths);
    private final Tombstones tombstones = new Tombstones(paths);
    private final FolderQuarantine folderQuarantine = new FolderQuarantine(paths);
    private final FieldIndexStore fieldIndexStore = new FieldIndexStore(paths);
    private final FieldIndexLoader fieldIndexLoader = new FieldIndexLoader(paths);
    private final DocumentPageStore documentPageStore = new DocumentPageStore(paths);
    private final PkIndexStore pkIndexStore = new PkIndexStore(paths);
    private final CompactionJournal compactionJournal = new CompactionJournal(paths);
    private final PageCompactor pageCompactor = new PageCompactor(paths, pkIndexStore, compactionJournal);
    private final CompactionRecovery compactionRecovery = new CompactionRecovery(paths, pkIndexStore,
            compactionJournal);

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
        return !dbFolder.exists() || FileTreeDeleter.delete(dbFolder);
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
        return !collectionFolder.exists() || FileTreeDeleter.delete(collectionFolder);
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

    public void markIndexesDirty(String dbName, String collName) {
        dirtyIndexMarkers.mark(dbName, collName);
    }

    public void clearIndexesDirty(String dbName, String collName) {
        dirtyIndexMarkers.clear(dbName, collName);
    }

    public List<String> listDirtyIndexCollections() {
        return dirtyIndexMarkers.listMarked();
    }

    public IndexBuildMarkers indexBuildMarkers() {
        return indexBuildMarkers;
    }

    public FolderQuarantine folderQuarantine() {
        return folderQuarantine;
    }

    public Tombstones tombstones() {
        return tombstones;
    }

    public List<PkIndexEntry> readWholePkIndexFile(String dbName, String collectionName) throws IOException {
        return pkIndexStore.readWholePkIndexFile(dbName, collectionName);
    }

    public DbEntry getById(PkIndexEntry pkIndexEntry) throws Exception {
        return documentPageStore.getById(pkIndexEntry);
    }

    public List<DbEntry> getByIndexEntries(List<PkIndexEntry> entries) throws IOException {
        return documentPageStore.getByIndexEntries(entries);
    }

    public <T extends DbEntry> List<IndexedDbEntry> bulkInsertIntoCollection(final String dbName, final String collName,
            final List<T> entries) throws IOException {
        return bulkInsertIntoCollection(dbName, collName, entries, DbEntry::getPage);
    }

    public <T extends DbEntry> List<IndexedDbEntry> bulkInsertIntoCollection(final String dbName, final String collName,
            final List<T> entries, final Function<? super T, Long> storagePageResolver) throws IOException {
        final var indexEntries = new ArrayList<IndexedDbEntry>();
        final var pkEntriesToIndex = new ArrayList<PkIndexEntry>();
        final var lengthsBeforeAppend = new LinkedHashMap<File, Long>();
        final var entrySet = entries.stream().collect(Collectors.groupingBy(storagePageResolver)).entrySet();
        try {
            for (var groupedEntry : entrySet) {
                final var page = groupedEntry.getKey();
                final var pageEntries = groupedEntry.getValue();
                final var file = paths.collectionPage(dbName, collName, page);
                final var lock = FileLocks.lockFor(file).writeLock();
                lock.lock();
                try (var writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8, true),
                        Globals.BUFFER_SIZE)) {
                    var currentOffset = file.length();
                    lengthsBeforeAppend.putIfAbsent(file, currentOffset);
                    final var separator = FileLocks.separatorBeforeAppend(file);
                    writer.append(separator);
                    currentOffset += separator.getBytes(StandardCharsets.UTF_8).length;
                    for (var entry : pageEntries) {
                        final var strData = entry.toFileEntry() + Globals.NEWLINE;
                        final var bytes = strData.getBytes(StandardCharsets.UTF_8);
                        final var length = bytes.length;
                        writer.append(strData);
                        final var pkEntry = new PkIndexEntry(dbName, collName, entry.get_id(), currentOffset, length,
                                page, entry.getVersion());
                        pkEntriesToIndex.add(pkEntry);
                        final var indexedEntry = new IndexedDbEntry();
                        indexedEntry.setIndex(pkEntry);
                        indexedEntry.setCollectionName(collName);
                        indexedEntry.setDatabaseName(dbName);
                        indexedEntry.set_id(entry.get_id());
                        indexedEntry.setData(entry.getData());
                        indexedEntry.setVersion(entry.getVersion());
                        indexEntries.add(indexedEntry);
                        currentOffset += length;
                    }
                } finally {
                    lock.unlock();
                }
            }
            pkIndexStore.bulkIndexNewPKValues(dbName, collName, pkEntriesToIndex);
        } catch (IOException e) {
            rollBackBulkAppends(lengthsBeforeAppend);
            throw e;
        }
        return indexEntries;
    }

    private void rollBackBulkAppends(Map<File, Long> lengthsBeforeAppend) {
        for (final var appended : lengthsBeforeAppend.entrySet()) {
            final var lock = FileLocks.lockFor(appended.getKey()).writeLock();
            lock.lock();
            try {
                PageRegions.truncateTo(appended.getKey(), appended.getValue());
            } finally {
                lock.unlock();
            }
        }
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry) throws IOException {
        final var dbName = entry.getDatabaseName();
        final var collName = entry.getCollectionName();
        final var page = entry.getPage();
        final var file = paths.collectionPage(dbName, collName, page);
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        try {
            final var strData = entry.toFileEntry() + Globals.NEWLINE;
            final var length = strData.getBytes(StandardCharsets.UTF_8).length;
            final var totalFileLength = file.length();
            final var separator = FileLocks.separatorBeforeAppend(file);
            try {
                appendToPage(file, separator + strData);
                return pkIndexStore.indexNewPKValue(dbName, collName, entry.get_id(),
                        totalFileLength + separator.getBytes(StandardCharsets.UTF_8).length, length, page,
                        entry.getVersion());
            } catch (IOException e) {
                PageRegions.truncateTo(file, totalFileLength);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    private void appendToPage(File file, String strData) throws IOException {
        try (var writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8, true), Globals.BUFFER_SIZE)) {
            writer.append(strData);
        }
    }

    public PkCompaction deleteFromCollection(PkIndexEntry pkIndexEntry) {
        return pageCompactor.delete(pkIndexEntry);
    }

    public PkCompaction deleteForRelocation(PkIndexEntry source) {
        return pageCompactor.deleteForRelocation(source);
    }

    public PkIndexEntry insertRelocated(DbEntry entry, PkIndexEntry source) throws IOException {
        pageCompactor.retargetRelocation(source, entry.getPage());
        return insertIntoCollection(entry);
    }

    public void endRelocation(PkIndexEntry source) {
        pageCompactor.endRelocation(source);
    }

    public List<PkIndexEntry> recoverInterruptedCompactions() throws IOException {
        return compactionRecovery.recoverAll().completedDeletes();
    }

    public List<String> listCompactionMarkers() {
        try {
            return compactionJournal.findMarkerFiles().stream().map(File::getPath).toList();
        } catch (IOException e) {
            return List.of(paths.dbPath() + " (could not be searched: " + e.getMessage() + ")");
        }
    }

    public BulkUpdateResult bulkUpdateFromCollection(String dbName, String collName, List<IndexedDbEntry> entries)
            throws IOException {
        return pageCompactor.bulkUpdate(dbName, collName, entries);
    }

    public UpdateResult updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry) throws IOException {
        return pageCompactor.update(entry, pkIndexEntry);
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

    public Map<Long, Long> pageFileLengths(String dbName, String collName) throws IOException {
        return documentPageStore.pageFileLengths(dbName, collName);
    }

    public void healTornPageTails(String dbName, String collName) throws IOException {
        TornPageTail.healAll(paths, dbName, collName, pageFileLengths(dbName, collName).keySet(),
                () -> readWholePkIndexFile(dbName, collName));
    }

    public List<DbEntry> adoptOrphanedRecords(String dbName, String collName) throws IOException {
        return OrphanedPageRecords.adoptAll(paths, pkIndexStore, dbName, collName,
                pageFileLengths(dbName, collName).keySet());
    }

    public long pageFileCount(String dbName, String collName) throws IOException {
        return documentPageStore.pageFileCount(dbName, collName);
    }

}
