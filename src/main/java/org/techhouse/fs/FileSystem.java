package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
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

public class FileSystem {
    private final Configuration configuration = Configuration.getInstance();
    private final FilePaths paths = new FilePaths();
    private final IndexMarkers indexMarkers = new IndexMarkers(paths);
    private final TombstoneStore tombstones = new TombstoneStore(paths);
    private final MetadataFiles metadataFiles = new MetadataFiles(paths);
    private final FolderQuarantine folderQuarantine = new FolderQuarantine(paths);
    private final FieldIndexStore fieldIndexStore = new FieldIndexStore(paths);
    private final FieldIndexLoader fieldIndexLoader = new FieldIndexLoader(paths);
    private final DocumentPageStore documentPageStore = new DocumentPageStore(paths);
    private final PkIndexStore pkIndexStore = new PkIndexStore(paths);
    private final WriteIntentJournal writeIntentJournal = new WriteIntentJournal(paths);
    private final PageVacuum pageVacuum = new PageVacuum(paths, pkIndexStore, writeIntentJournal);
    private final RecordReplacer recordReplacer = new RecordReplacer(paths, pkIndexStore, pageVacuum);
    private final PageAppender pageAppender = new PageAppender(paths, pkIndexStore, pageVacuum);
    private final WriteIntentRecovery writeIntentRecovery = new WriteIntentRecovery(paths, pkIndexStore,
            writeIntentJournal);
    private final NodeIdStore nodeIdStore = new NodeIdStore(paths);
    private final WriteAheadLog writeAheadLog = new WriteAheadLog(paths);
    private final WalCheckpoints walCheckpoints = new WalCheckpoints(paths, writeAheadLog);

    public void onVacuum(Consumer<VacuumedPage> listener) {
        pageVacuum.onVacuum(listener);
    }

    public void guardCheckpoints(CheckpointGuard guard) {
        WriteAheadLog.guardWith(guard);
    }

    public void createBaseDbPath() {
        paths.useDbPath(configuration.getFilePath());
        final var directory = new File(paths.dbPath());
        if (!directory.exists()) {
            try {
                OwnerOnly.createDirectories(directory.toPath());
            } catch (IOException e) {
                throw new DirectoryNotFoundException(directory.getAbsolutePath());
            }
        }
    }

    public void verifyDeviceSync() throws IOException {
        SyncedWrites.verifyFolderSync(new File(paths.dbPath()).toPath());
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
            OwnerOnly.createDirectories(pagesFolder.toPath());
        } catch (IOException e) {
            throw new DirectoryNotFoundException(pagesFolder.getAbsolutePath());
        }
    }

    public boolean createDatabaseFolder(String dbName) {
        final var dbFolder = paths.rawDatabaseFolder(dbName);
        if (!dbFolder.exists()) {
            return OwnerOnly.createDirectory(dbFolder.toPath());
        }
        return true;
    }

    public boolean deleteDatabase(String dbName) {
        final var dbFolder = paths.rawDatabaseFolder(dbName);
        final var deleted = isAbsent(dbFolder) || FileTreeDeleter.delete(dbFolder);
        FileLocks.evictAllUnder(dbFolder);
        paths.forgetDatabase(dbName);
        return deleted;
    }

    public boolean createCollectionFile(String dbName, String collectionName) throws IOException {
        final var collectionFile = paths.collectionPage(dbName, collectionName, 0);
        final var collectionFolder = new File(collectionFile.getParent());
        if (!collectionFolder.exists()) {
            if (OwnerOnly.createDirectory(collectionFolder.toPath())) {
                return OwnerOnly.createNewOwnerOnlyFile(collectionFile.toPath());
            } else {
                return false;
            }
        } else {
            if (!collectionFile.exists()) {
                return OwnerOnly.createNewOwnerOnlyFile(collectionFile.toPath());
            }
            return true;
        }
    }

    public boolean deleteCollectionFiles(String dbName, String collectionName) {
        final var collectionFile = paths.collectionPage(dbName, collectionName, 0);
        final var collectionFolder = new File(collectionFile.getParent());
        final var deleted = isAbsent(collectionFolder) || FileTreeDeleter.delete(collectionFolder);
        FileLocks.evictAllUnder(collectionFolder);
        paths.forgetCollection(dbName, collectionName);
        return deleted;
    }

    private static boolean isAbsent(File folder) {
        return !Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    public MetadataFiles metadataFiles() {
        return metadataFiles;
    }

    public IndexMarkers indexMarkers() {
        return indexMarkers;
    }

    public FolderQuarantine folderQuarantine() {
        return folderQuarantine;
    }

    public Path nodeIdFile() {
        return nodeIdStore.file();
    }

    public String readNodeId() throws IOException {
        return nodeIdStore.read();
    }

    public void writeNodeId(String nodeId) throws IOException {
        nodeIdStore.write(nodeId);
    }

    public TombstoneStore tombstones() {
        return tombstones;
    }

    public List<PkIndexEntry> readWholePkIndexFile(String dbName, String collectionName) throws IOException {
        return pkIndexStore.readWholePkIndexFile(dbName, collectionName);
    }

    public Map<String, PkIndexEntry> pkRowsFor(String dbName, String collName, Collection<String> ids)
            throws IOException {
        return pkIndexStore.rowsFor(dbName, collName, ids);
    }

    public OptionalLong trustedLiveRows(String dbName, String collName) {
        return pkIndexStore.trustedLiveRows(dbName, collName);
    }

    public long pkIndexFileBytes(String dbName, String collectionName) {
        return paths.pkIndexFile(dbName, collectionName).length();
    }

    public long indexFileBytes(String dbName, String collName, String fieldName, String indexTypeLabel) {
        final var indexFile = paths.indexFile(dbName, collName, fieldName, indexTypeLabel);
        return indexFile == null ? 0L : indexFile.length() + FilePaths.logFileOf(indexFile).length();
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

    public <T extends DbEntry> List<IndexedDbEntry> writeBatch(String dbName, String collName, List<T> inserts,
            Function<? super T, Long> pageOf, Function<? super T, byte[]> lines, List<PkIndexEntry> deletes)
            throws IOException {
        return pageAppender.bulkInsertAndDelete(dbName, collName, inserts, pageOf, lines, deletes);
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry) throws IOException {
        return pageAppender.insert(entry, entry.encodeLine());
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry, byte[] encodedLine) throws IOException {
        return pageAppender.insert(entry, encodedLine);
    }

    public void deleteFromCollection(PkIndexEntry pkIndexEntry) throws IOException {
        recordReplacer.delete(pkIndexEntry);
    }

    public void deleteFromCollection(PkIndexEntry pkIndexEntry, List<PkIndexEntry> residentRows,
            LongFunction<PageOccupancy> occupancy) throws IOException {
        recordReplacer.delete(pkIndexEntry, residentRows, occupancy);
    }

    public void vacuumPagesAfterDelete(String dbName, String collName, Collection<Long> pages,
            List<PkIndexEntry> residentRows, LongFunction<PageOccupancy> occupancy) {
        recordReplacer.vacuumAfterDeletes(dbName, collName, pages, residentRows, occupancy);
    }

    public PkIndexEntry relocate(DbEntry entry, PkIndexEntry oldRow, long targetPage, byte[] encodedLine,
            List<PkIndexEntry> residentRows, LongFunction<PageOccupancy> occupancy) throws IOException {
        entry.setPage(targetPage);
        return recordReplacer.replace(entry, encodedLine, oldRow, targetPage, null, residentRows, occupancy);
    }

    public List<PkIndexEntry> redoWriteAheadLogs() throws IOException {
        return WalRedo.redoAll(paths);
    }

    public void recoverWriteIntents() throws IOException {
        writeIntentRecovery.recoverAll();
        cutUncommittedAdminCopies();
    }

    private void cutUncommittedAdminCopies() throws IOException {
        for (final var collName : Globals.ADMIN_COLLECTION_NAMES) {
            cutUncommittedCopiesOf(Globals.ADMIN_DB_NAME, collName);
            cutUncommittedCopiesOf(Globals.ADMIN_PAGES_DB_NAME,
                    AdminPageEntry.pagesCollectionName(Globals.ADMIN_DB_NAME, collName));
        }
    }

    private void cutUncommittedCopiesOf(String dbName, String collName) throws IOException {
        if (paths.collectionFolder(dbName, collName).isDirectory()) {
            OrphanedPageRecords.cutUncommittedCopies(paths, pkIndexStore, dbName, collName,
                    pageFileLengths(dbName, collName).keySet());
        }
    }

    public List<IndexedDbEntry> bulkUpdateFromCollection(String dbName, String collName, List<IndexedDbEntry> entries)
            throws IOException {
        return bulkUpdateFromCollection(dbName, collName, entries, null, null);
    }

    public List<IndexedDbEntry> bulkUpdateFromCollection(String dbName, String collName, List<IndexedDbEntry> entries,
            List<PkIndexEntry> residentRows, Function<? super IndexedDbEntry, byte[]> encodedLines) throws IOException {
        return bulkUpdateFromCollection(dbName, collName, entries, residentRows, encodedLines, null);
    }

    public List<IndexedDbEntry> bulkUpdateFromCollection(String dbName, String collName, List<IndexedDbEntry> entries,
            List<PkIndexEntry> residentRows, Function<? super IndexedDbEntry, byte[]> encodedLines,
            LongFunction<PageOccupancy> occupancy) throws IOException {
        return recordReplacer.bulkReplace(dbName, collName, entries, residentRows, encodedLines, occupancy);
    }

    public PkIndexEntry updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry) throws IOException {
        return updateFromCollection(entry, pkIndexEntry, entry.encodeLine(), null);
    }

    public PkIndexEntry updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry, byte[] encodedLine,
            List<PkIndexEntry> residentRows) throws IOException {
        return updateFromCollection(entry, pkIndexEntry, encodedLine, null, residentRows, null);
    }

    public PkIndexEntry updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry, byte[] encodedLine,
            PagePlacement placement, List<PkIndexEntry> residentRows, LongFunction<PageOccupancy> occupancy)
            throws IOException {
        return recordReplacer.replace(entry, encodedLine, pkIndexEntry, pkIndexEntry.getPage(), placement, residentRows,
                occupancy);
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
        return indexFile != null && (indexFile.exists() || FilePaths.logFileOf(indexFile).exists());
    }

    public void appendIndexLog(String dbName, String collName, String fieldName, String typeLabel,
            List<IndexLogChange> changes, List<? extends FieldIndexEntry<?>> survivors) throws IOException {
        fieldIndexStore.appendIndexLog(dbName, collName, fieldName, typeLabel, changes, survivors);
    }

    public boolean appendIndexLogOnly(String dbName, String collName, String fieldName, String typeLabel,
            List<IndexLogChange> changes, IndexTypeReader foldSource) throws IOException {
        return fieldIndexStore.appendIndexLogOnly(dbName, collName, fieldName, typeLabel, changes, foldSource);
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

    public long pageFileLength(String dbName, String collName, long page) {
        return paths.collectionPage(dbName, collName, page).length();
    }

    public void healTornPageTails(String dbName, String collName) throws IOException {
        healTornPageTails(pkRowsSession(dbName, collName));
    }

    public PkRowsSession pkRowsSession(String dbName, String collName) {
        return new PkRowsSession(pkIndexStore, dbName, collName);
    }

    public long highestPkVersion(String dbName, String collName) throws IOException {
        return pkIndexStore.highestVersion(dbName, collName);
    }

    public void healTornPageTails(PkRowsSession session) throws IOException {
        TornPageTail.healAll(paths, session.dbName(), session.collName(),
                pageFileLengths(session.dbName(), session.collName()).keySet(), session::rows);
    }

    public List<PkIndexEntry> retireDanglingPkEntries(PkRowsSession session) throws IOException {
        return OrphanedPageRecords.retireDangling(session, pageFileLengths(session.dbName(), session.collName()));
    }

    public void checkpointWriteAheadLogs(long deadline) {
        walCheckpoints.atShutdown(deadline);
    }

    public int checkpointIdleWriteAheadLogs(long idleMillis, int maxLogs, CollectionTryLock collectionLocks) {
        return walCheckpoints.idle(idleMillis, maxLogs, collectionLocks);
    }

    public void openWriteGroup(String dbName, String collName) throws IOException {
        writeAheadLog.openGroup(dbName, collName);
    }

    public boolean reopenWriteGroupIfEnded(String dbName, String collName) throws IOException {
        if (writeAheadLog.openGroupOf(dbName, collName) != null) {
            return false;
        }
        writeAheadLog.openGroup(dbName, collName);
        return true;
    }

    public boolean forceWriteGroup(String dbName, String collName) throws IOException {
        return writeAheadLog.forceGroup(dbName, collName);
    }

    public boolean settleWriteGroup(String dbName, String collName) throws IOException {
        return writeAheadLog.closeGroup(dbName, collName);
    }

    public PkRebuild planPkRebuild(String dbName, String collName) throws IOException {
        writeAheadLog.checkpoint(dbName, collName);
        if (pkIndexStore.readRecognisedPkIndex(dbName, collName).isEmpty()) {
            writeIntentRecovery.recoverPagesOf(dbName, collName);
        } else {
            refuseStaleWriteIntents(dbName, collName);
        }
        return PkIndexRebuild.plan(paths, pkIndexStore, dbName, collName, pageFileLengths(dbName, collName).keySet());
    }

    public void applyPkRebuild(String dbName, String collName, PkRebuild rebuild) throws IOException {
        PkIndexRebuild.apply(pkIndexStore, dbName, collName, rebuild);
    }

    private void refuseStaleWriteIntents(String dbName, String collName) throws IOException {
        final var stale = writeIntentRecovery.replayableIntentsOf(dbName, collName);
        if (!stale.isEmpty()) {
            throw PkIndexRebuild.refused(dbName, collName, "write intent(s) " + stale + " were left by writes this"
                    + " process could not clean up after, and a rebuild would freeze whatever half of each write the"
                    + " pages hold. Restart the server, which recovers them");
        }
    }

    public List<DbEntry> adoptOrphanedRecords(PkRowsSession session) throws IOException {
        return OrphanedPageRecords.adoptAll(paths, session,
                pageFileLengths(session.dbName(), session.collName()).keySet());
    }

    public long pageFileCount(String dbName, String collName) throws IOException {
        return documentPageStore.pageFileCount(dbName, collName);
    }

}
