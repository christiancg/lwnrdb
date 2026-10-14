package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ex.DirectoryNotFoundException;
import org.techhouse.utils.OwnerOnlyPermissions;

public class FileSystem {
    private final FilePaths paths = new FilePaths();
    private final IndexMarkers indexMarkers = new IndexMarkers(paths);
    private final TombstoneStore tombstones = new TombstoneStore(paths);
    private final FolderQuarantine folderQuarantine = new FolderQuarantine(paths);
    private final FieldIndexStore fieldIndexStore = new FieldIndexStore(paths);
    private final FieldIndexLoader fieldIndexLoader = new FieldIndexLoader(paths);
    private final DocumentPageStore documentPageStore = new DocumentPageStore(paths);
    private final PkIndexStore pkIndexStore = new PkIndexStore(paths);
    private final CompactionJournal compactionJournal = new CompactionJournal(paths);
    private final PageCompactor pageCompactor = new PageCompactor(paths, pkIndexStore, compactionJournal);
    private final PageAppender pageAppender = new PageAppender(paths, pkIndexStore);
    private final CompactionRecovery compactionRecovery = new CompactionRecovery(paths, pkIndexStore,
            compactionJournal);

    public void createBaseDbPath() {
        paths.useDbPath(Configuration.getInstance().getFilePath());
        final var directory = new File(paths.dbPath());
        if (!directory.exists()) {
            try {
                OwnerOnlyPermissions.createDirectories(directory.toPath());
            } catch (IOException e) {
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
        final var pagesDatabases = AdminPageEntry.pagesCollectionName(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_DATABASES_COLLECTION_NAME);
        final var pagesCollections = AdminPageEntry.pagesCollectionName(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        final var pagesUsers = AdminPageEntry.pagesCollectionName(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_USERS_COLLECTION_NAME);
        final var pagesCollectionUsage = AdminPageEntry.pagesCollectionName(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_COLLECTION_USAGE_NAME);
        final var pagesTransactions = AdminPageEntry.pagesCollectionName(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        final var pagesTriggerRuns = AdminPageEntry.pagesCollectionName(Globals.ADMIN_DB_NAME,
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
        if (pagesFolder.exists()) {
            return;
        }
        try {
            OwnerOnlyPermissions.createDirectories(pagesFolder.toPath());
        } catch (IOException e) {
            throw new DirectoryNotFoundException(pagesFolder.getAbsolutePath());
        }
    }

    public boolean createDatabaseFolder(String dbName) {
        final var dbFolder = paths.rawDatabaseFolder(dbName);
        if (!dbFolder.exists()) {
            return OwnerOnlyPermissions.createDirectory(dbFolder.toPath());
        }
        return true;
    }

    public boolean deleteDatabase(String dbName) {
        final var dbFolder = paths.rawDatabaseFolder(dbName);
        final var deleted = isAbsent(dbFolder) || FileTreeDeleter.delete(dbFolder);
        FileLocks.evictAllUnder(dbFolder);
        return deleted;
    }

    public boolean createCollectionFile(String dbName, String collectionName) throws IOException {
        final var collectionFile = paths.collectionPage(dbName, collectionName, 0);
        final var collectionFolder = new File(collectionFile.getParent());
        if (!collectionFolder.exists()) {
            if (OwnerOnlyPermissions.createDirectory(collectionFolder.toPath())) {
                return OwnerOnlyPermissions.createNewOwnerOnlyFile(collectionFile.toPath());
            } else {
                return false;
            }
        } else {
            if (!collectionFile.exists()) {
                return OwnerOnlyPermissions.createNewOwnerOnlyFile(collectionFile.toPath());
            }
            return true;
        }
    }

    public boolean deleteCollectionFiles(String dbName, String collectionName) {
        final var collectionFile = paths.collectionPage(dbName, collectionName, 0);
        final var collectionFolder = new File(collectionFile.getParent());
        final var deleted = isAbsent(collectionFolder) || FileTreeDeleter.delete(collectionFolder);
        FileLocks.evictAllUnder(collectionFolder);
        return deleted;
    }

    private static boolean isAbsent(File folder) {
        return !Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    public void writeCollectionSchema(String dbName, String collName, String schemaJson) throws IOException {
        MetadataFileStore.write(paths.schemaFile(dbName, collName), schemaJson);
    }

    public String readCollectionSchema(String dbName, String collName) throws IOException {
        return MetadataFileStore.read(paths.schemaFile(dbName, collName));
    }

    public boolean deleteCollectionSchema(String dbName, String collName) throws IOException {
        return MetadataFileStore.delete(paths.schemaFile(dbName, collName));
    }

    public void writeProcedure(String dbName, String name, String json) throws IOException {
        MetadataFileStore.ensureFolder(paths.proceduresFolder(dbName), "procedures", dbName);
        MetadataFileStore.write(paths.procedureFile(dbName, name), json);
    }

    public String readProcedure(String dbName, String name) throws IOException {
        final var file = paths.procedureFile(dbName, name);
        return MetadataFileStore.isListedExactly(file) ? MetadataFileStore.read(file) : null;
    }

    public boolean deleteProcedure(String dbName, String name) throws IOException {
        final var file = paths.procedureFile(dbName, name);
        return MetadataFileStore.isListedExactly(file) && MetadataFileStore.delete(file);
    }

    public List<String> listProcedureNames(String dbName) throws IOException {
        return MetadataFileStore.listNames(paths.proceduresFolder(dbName), Globals.PROCEDURE_FILE_EXTENSION);
    }

    public void writeSchedule(String dbName, String name, String json) throws IOException {
        MetadataFileStore.ensureFolder(paths.schedulesFolder(dbName), "schedules", dbName);
        MetadataFileStore.write(paths.scheduleFile(dbName, name), json);
    }

    public String readSchedule(String dbName, String name) throws IOException {
        final var file = paths.scheduleFile(dbName, name);
        return MetadataFileStore.isListedExactly(file) ? MetadataFileStore.read(file) : null;
    }

    public boolean deleteSchedule(String dbName, String name) throws IOException {
        final var file = paths.scheduleFile(dbName, name);
        return MetadataFileStore.isListedExactly(file) && MetadataFileStore.delete(file);
    }

    public List<String> listScheduleNames(String dbName) throws IOException {
        return MetadataFileStore.listNames(paths.schedulesFolder(dbName), Globals.SCHEDULE_FILE_EXTENSION);
    }

    public void writeTriggers(String dbName, String collName, String json) throws IOException {
        MetadataFileStore.write(paths.triggersFile(dbName, collName), json);
    }

    public String readTriggers(String dbName, String collName) throws IOException {
        return MetadataFileStore.read(paths.triggersFile(dbName, collName));
    }

    public boolean deleteTriggers(String dbName, String collName) throws IOException {
        return MetadataFileStore.delete(paths.triggersFile(dbName, collName));
    }

    public IndexMarkers indexMarkers() {
        return indexMarkers;
    }

    public FolderQuarantine folderQuarantine() {
        return folderQuarantine;
    }

    public TombstoneStore tombstones() {
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
        return pageAppender.bulkInsert(dbName, collName, entries, storagePageResolver, DbEntry::encodeLine);
    }

    public <T extends DbEntry> List<IndexedDbEntry> bulkInsertIntoCollection(final String dbName, final String collName,
            final List<T> entries, final Function<? super T, Long> storagePageResolver,
            final Function<? super T, byte[]> encodedLines) throws IOException {
        return pageAppender.bulkInsert(dbName, collName, entries, storagePageResolver, encodedLines);
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry) throws IOException {
        return pageAppender.insert(entry, entry.encodeLine());
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry, byte[] encodedLine) throws IOException {
        return pageAppender.insert(entry, encodedLine);
    }

    public PkCompaction deleteFromCollection(PkIndexEntry pkIndexEntry) {
        return deleteFromCollection(pkIndexEntry, null);
    }

    public PkCompaction deleteFromCollection(PkIndexEntry pkIndexEntry, List<PkIndexEntry> residentRows) {
        return pageCompactor.delete(pkIndexEntry, residentRows);
    }

    public PkCompaction deleteForRelocation(PkIndexEntry source, List<PkIndexEntry> residentRows) {
        return pageCompactor.deleteForRelocation(source, residentRows);
    }

    public PkIndexEntry insertRelocated(DbEntry entry, PkIndexEntry source) throws IOException {
        return insertRelocated(entry, source, entry.encodeLine());
    }

    public PkIndexEntry insertRelocated(DbEntry entry, PkIndexEntry source, byte[] encodedLine) throws IOException {
        pageCompactor.retargetRelocation(source, entry.getPage());
        return insertIntoCollection(entry, encodedLine);
    }

    public void endRelocation(PkIndexEntry source) {
        pageCompactor.endRelocation(source);
    }

    public List<PkIndexEntry> recoverInterruptedCompactions() throws IOException {
        return compactionRecovery.recoverAll();
    }

    public BulkUpdateResult bulkUpdateFromCollection(String dbName, String collName, List<IndexedDbEntry> entries)
            throws IOException {
        return bulkUpdateFromCollection(dbName, collName, entries, null, null);
    }

    public BulkUpdateResult bulkUpdateFromCollection(String dbName, String collName, List<IndexedDbEntry> entries,
            List<PkIndexEntry> residentRows, Function<? super IndexedDbEntry, byte[]> encodedLines) throws IOException {
        return pageCompactor.bulkUpdate(dbName, collName, entries, residentRows, encodedLines);
    }

    public UpdateResult updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry) throws IOException {
        return updateFromCollection(entry, pkIndexEntry, entry.encodeLine(), null);
    }

    public UpdateResult updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry, byte[] encodedLine,
            List<PkIndexEntry> residentRows) throws IOException {
        return pageCompactor.update(entry, encodedLine, pkIndexEntry, residentRows);
    }

    public void writeIndexFile(String dbName, String collName, String fieldName,
            Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap) {
        fieldIndexStore.writeIndexFile(dbName, collName, fieldName, indexEntryMap);
    }

    public void writeHashIndexFile(String dbName, String collName, String fieldName, IndexKind kind,
            List<FieldIndexEntry<String>> entries) {
        fieldIndexStore.writeHashIndexFile(dbName, collName, fieldName, kind, entries);
    }

    public boolean indexTypeFileExists(String dbName, String collName, String fieldName, String typeLabel) {
        final var indexFile = paths.indexFile(dbName, collName, fieldName, typeLabel);
        return indexFile != null && indexFile.exists();
    }

    public void rewriteIndexType(String dbName, String collName, String fieldName, String typeLabel,
            List<? extends FieldIndexEntry<?>> entries) throws IOException {
        fieldIndexStore.rewriteIndexType(dbName, collName, fieldName, typeLabel, entries);
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

    public List<PkIndexEntry> retireDanglingPkEntries(String dbName, String collName) throws IOException {
        return OrphanedPageRecords.retireDangling(pkIndexStore, dbName, collName, pageFileLengths(dbName, collName));
    }

    public PkRebuild planPkRebuild(String dbName, String collName) throws IOException {
        if (pkIndexStore.readRecognisedPkIndex(dbName, collName).isEmpty()) {
            compactionRecovery.recoverPagesOf(dbName, collName);
        } else {
            refuseStaleCompactionMarkers(dbName, collName);
        }
        return PkIndexRebuild.plan(paths, pkIndexStore, dbName, collName, pageFileLengths(dbName, collName).keySet());
    }

    public void applyPkRebuild(String dbName, String collName, PkRebuild rebuild) throws IOException {
        PkIndexRebuild.apply(pkIndexStore, dbName, collName, rebuild);
    }

    private void refuseStaleCompactionMarkers(String dbName, String collName) throws IOException {
        final var stale = compactionRecovery.replayableMarkersOf(dbName, collName);
        if (!stale.isEmpty()) {
            throw PkIndexRebuild.refused(dbName, collName, "compaction marker(s) " + stale + " were left by writes"
                    + " this process could not clean up after; their saved tails are stale, and replaying one would"
                    + " truncate every record written since. Fix what stopped their removal, then delete them");
        }
    }

    public List<DbEntry> adoptOrphanedRecords(String dbName, String collName) throws IOException {
        return OrphanedPageRecords.adoptAll(paths, pkIndexStore, dbName, collName,
                pageFileLengths(dbName, collName).keySet());
    }

    public long pageFileCount(String dbName, String collName) throws IOException {
        return documentPageStore.pageFileCount(dbName, collName);
    }

}
