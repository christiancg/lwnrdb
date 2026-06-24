package org.techhouse.fs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.DirectoryNotFoundException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.utils.ReflectionUtils;

public class FileSystem {
    private static final Logger logger = Logger.logFor(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private String dbPath;

    // Per-file read/write locks guaranteeing physical-I/O atomicity: a file's bytes are never read
    // while they are being rewritten. This is the finer-grained tier below the collection-level
    // locks in ResourceLocking, and is what makes dirty reads safe (a dirty read skips the
    // collection lock but still serializes against the in-progress physical write of each file).
    private static final Map<String, ReentrantReadWriteLock> fileLocks = new ConcurrentHashMap<>();

    private ReentrantReadWriteLock fileLock(File file) {
        return fileLocks.computeIfAbsent(file.getAbsolutePath(), _ -> new ReentrantReadWriteLock());
    }

    public void createBaseDbPath() {
        dbPath = Configuration.getInstance().getFilePath();
        final var directory = new File(dbPath);
        if (!directory.exists()) {
            var result = directory.mkdir();
            if (!result) {
                throw new DirectoryNotFoundException(directory.getAbsolutePath());
            }
        }
    }

    public void createAdminDatabase() throws IOException {
        createDatabaseFolder(Globals.ADMIN_DB_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME);
        final var pagesDatabases = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME,
                Globals.ADMIN_DATABASES_COLLECTION_NAME);
        final var pagesCollections = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME,
                Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        final var pagesUsers = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME,
                Globals.ADMIN_USERS_COLLECTION_NAME);
        final var pagesCollectionUsage = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME,
                Globals.ADMIN_COLLECTION_USAGE_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, pagesDatabases);
        createCollectionFile(Globals.ADMIN_DB_NAME, pagesCollections);
        createCollectionFile(Globals.ADMIN_DB_NAME, pagesUsers);
        createCollectionFile(Globals.ADMIN_DB_NAME, pagesCollectionUsage);
    }

    public boolean createDatabaseFolder(String dbName) {
        final var dbFolder = new File(dbPath + Globals.FILE_SEPARATOR + dbName);
        if (!dbFolder.exists()) {
            return dbFolder.mkdir();
        }
        return true;
    }

    public boolean deleteDatabase(String dbName) {
        final var dbFolder = new File(dbPath + Globals.FILE_SEPARATOR + dbName);
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

    private File getCollectionFolder(String dbName, String collectionName) {
        return new File(dbPath + Globals.FILE_SEPARATOR + dbName + Globals.FILE_SEPARATOR + collectionName);
    }

    private File getCollectionFile(String dbName, String collectionName, long page) {
        return new File(dbPath + Globals.FILE_SEPARATOR + dbName + Globals.FILE_SEPARATOR + collectionName
                + Globals.FILE_SEPARATOR + collectionName + Globals.FILE_PAGE_SEPARATOR + page
                + Globals.DB_FILE_EXTENSION);
    }

    public boolean createCollectionFile(String dbName, String collectionName) throws IOException {
        final var collectionFile = getCollectionFile(dbName, collectionName, 0);
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
        final var collectionFile = getCollectionFile(dbName, collectionName, 0);
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

    private File getIndexFile(String dbName, String collectionName, String indexName, String indexType) {
        return new File(dbPath + Globals.FILE_SEPARATOR + dbName + Globals.FILE_SEPARATOR + collectionName
                + Globals.FILE_SEPARATOR + collectionName + Globals.INDEX_FILE_NAME_SEPARATOR + indexName
                + Globals.INDEX_FILE_NAME_SEPARATOR + indexType + Globals.INDEX_FILE_EXTENSION);
    }

    public DbEntry getById(PkIndexEntry pkIndexEntry) throws Exception {
        final var file = getCollectionFile(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName(),
                pkIndexEntry.getPage());
        final var lock = fileLock(file).readLock();
        lock.lock();
        try (var reader = new RandomAccessFile(file, Globals.R_PERMISSIONS)) {
            return readEntryFromOpenFile(reader, pkIndexEntry);
        } finally {
            lock.unlock();
        }
    }

    private DbEntry readEntryFromOpenFile(RandomAccessFile reader, PkIndexEntry pkIndexEntry) throws IOException {
        reader.seek(pkIndexEntry.getPosition());
        final var entryLength = (int) pkIndexEntry.getLength();
        byte[] buffer = new byte[entryLength];
        reader.readFully(buffer, 0, entryLength);
        final var strEntry = new String(buffer);
        final var jsonObject = eJson.fromJson(strEntry, JsonObject.class);
        final var entry = new DbEntry();
        entry.setDatabaseName(pkIndexEntry.getDatabaseName());
        entry.setCollectionName(pkIndexEntry.getCollectionName());
        entry.set_id(pkIndexEntry.getValue());
        jsonObject.remove(Globals.PK_FIELD);
        entry.setData(jsonObject);
        return entry;
    }

    public List<DbEntry> getByIndexEntries(List<PkIndexEntry> entries) throws IOException {
        final var result = new ArrayList<DbEntry>();
        if (entries == null || entries.isEmpty()) {
            return result;
        }
        final var byPage = entries.stream().collect(Collectors.groupingBy(PkIndexEntry::getPage));
        for (var pageGroup : byPage.entrySet()) {
            final var first = pageGroup.getValue().getFirst();
            final var file = getCollectionFile(first.getDatabaseName(), first.getCollectionName(), pageGroup.getKey());
            // Read the page's requested entries in ascending position order for sequential seeks.
            final var pageEntries = pageGroup.getValue().stream()
                    .sorted(Comparator.comparingLong(PkIndexEntry::getPosition)).toList();
            final var lock = fileLock(file).readLock();
            lock.lock();
            try (var reader = new RandomAccessFile(file, Globals.R_PERMISSIONS)) {
                for (var pkEntry : pageEntries) {
                    result.add(readEntryFromOpenFile(reader, pkEntry));
                }
            } finally {
                lock.unlock();
            }
        }
        return result;
    }

    public <T extends DbEntry> List<IndexedDbEntry> bulkInsertIntoCollection(final String dbName, final String collName,
            final List<T> entries) throws IOException {
        final var indexEntries = new ArrayList<IndexedDbEntry>();
        final var pkEntriesToIndex = new ArrayList<PkIndexEntry>();
        final var entrySet = entries.stream().collect(Collectors.groupingBy(DbEntry::getPage)).entrySet();
        for (var groupedEntry : entrySet) {
            final var page = groupedEntry.getKey();
            final var pageEntries = groupedEntry.getValue();
            final var file = getCollectionFile(dbName, collName, page);
            var currentOffset = file.length();
            final var lock = fileLock(file).writeLock();
            lock.lock();
            try (var writer = new BufferedWriter(new FileWriter(file, true), Globals.BUFFER_SIZE)) {
                for (var entry : pageEntries) {
                    final var strData = entry.toFileEntry() + Globals.NEWLINE;
                    final var bytes = strData.getBytes(StandardCharsets.UTF_8);
                    final var length = bytes.length;
                    writer.append(strData);
                    final var pkEntry = new PkIndexEntry(dbName, collName, entry.get_id(), currentOffset, length, page);
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
        bulkIndexNewPKValues(dbName, collName, pkEntriesToIndex);
        return indexEntries;
    }

    private void bulkIndexNewPKValues(String dbName, String collName, List<PkIndexEntry> pkEntries) throws IOException {
        final var indexFile = getIndexFile(dbName, collName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        final var lock = fileLock(indexFile).writeLock();
        lock.lock();
        try (var writer = new BufferedWriter(new FileWriter(indexFile, true), Globals.BUFFER_SIZE)) {
            for (var pkEntry : pkEntries) {
                writer.append(pkEntry.toFileEntry());
                writer.newLine();
            }
        } finally {
            lock.unlock();
        }
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry) throws IOException {
        final var dbName = entry.getDatabaseName();
        final var collName = entry.getCollectionName();
        final var page = entry.getPage();
        final var file = getCollectionFile(dbName, collName, page);
        final var lock = fileLock(file).writeLock();
        lock.lock();
        try (var writer = new BufferedWriter(new FileWriter(file, true), Globals.BUFFER_SIZE)) {
            final var strData = entry.toFileEntry() + Globals.NEWLINE;
            final var bytes = strData.getBytes(StandardCharsets.UTF_8);
            final var length = bytes.length;
            var totalFileLength = file.length();
            writer.append(strData);
            final var entryId = entry.get_id();
            return indexNewPKValue(entry.getDatabaseName(), entry.getCollectionName(), entryId, totalFileLength, length,
                    page);
        } finally {
            lock.unlock();
        }
    }

    private PkIndexEntry indexNewPKValue(String dbName, String collectionName, String value, long position, int length,
            long page) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        final var lock = fileLock(indexFile).writeLock();
        lock.lock();
        try (var writer = new BufferedWriter(new FileWriter(indexFile, true), Globals.BUFFER_SIZE)) {
            final var indexEntry = new PkIndexEntry(dbName, collectionName, value, position, length, page);
            writer.append(indexEntry.toFileEntry());
            writer.newLine();
            return indexEntry;
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
        final var file = getCollectionFile(dbName, collName, page);
        final long totalFileLength = file.length();
        final var lock = fileLock(file).writeLock();
        lock.lock();
        try (var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            final var compacted = shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
            writer.setLength(totalFileLength - pkIndexEntry.getLength());
            deleteIndexValue(pkIndexEntry);
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

    private void deleteIndexValue(PkIndexEntry pkIndexEntry) throws IOException {
        internalUpdatePKIndex(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName(), pkIndexEntry.getValue(),
                null);
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

    public List<IndexedDbEntry> bulkUpdateFromCollection(String dbName, String collName, List<IndexedDbEntry> entries)
            throws IOException {
        final var result = new ArrayList<IndexedDbEntry>();
        final var entrySet = entries.stream()
                .collect(Collectors.groupingBy(indexedDbEntry -> indexedDbEntry.getIndex().getPage())).entrySet();
        for (var groupedEntry : entrySet) {
            final var page = groupedEntry.getKey();
            final var pageEntries = groupedEntry.getValue();
            final var file = getCollectionFile(dbName, collName, page);
            long totalFileLength = file.length();
            final var newPkEntries = new ArrayList<PkIndexEntry>();
            final var oldLengths = new LinkedHashMap<String, Long>();
            final var lock = fileLock(file).writeLock();
            lock.lock();
            try (var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
                for (var entry : pageEntries) {
                    shiftOtherEntriesToStart(writer, entry.getIndex(), totalFileLength);
                    writer.seek(totalFileLength - entry.getIndex().getLength());
                    final var strData = entry.toFileEntry() + Globals.NEWLINE;
                    final var bytes = strData.getBytes(StandardCharsets.UTF_8);
                    final var length = bytes.length;
                    writer.write(bytes, 0, length);
                    final var newLength = totalFileLength - entry.getIndex().getLength() + length;
                    writer.setLength(newLength);
                    final var newPosition = totalFileLength - entry.getIndex().getLength();
                    final var newPkEntry = new PkIndexEntry(dbName, collName, entry.get_id(), newPosition, length,
                            page);
                    newPkEntries.add(newPkEntry);
                    oldLengths.put(entry.get_id(), entry.getIndex().getLength());
                    final var updatedIndexEntry = new IndexedDbEntry();
                    updatedIndexEntry.setIndex(newPkEntry);
                    updatedIndexEntry.set_id(entry.get_id());
                    updatedIndexEntry.setCollectionName(collName);
                    updatedIndexEntry.setDatabaseName(dbName);
                    updatedIndexEntry.setData(entry.getData());
                    updatedIndexEntry.setPreviousByteSize(entry.getIndex().getLength());
                    result.add(updatedIndexEntry);
                    totalFileLength = newLength;
                }
            } finally {
                lock.unlock();
            }
            bulkUpdateIndexValues(dbName, collName, newPkEntries, oldLengths);
        }
        return result;
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
        final var file = getCollectionFile(dbName, collName, page);
        final long totalFileLength = file.length();
        final var lock = fileLock(file).writeLock();
        lock.lock();
        try (var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            final var compacted = shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
            writer.seek(totalFileLength - pkIndexEntry.getLength());
            final var strData = entry.toFileEntry() + Globals.NEWLINE;
            final var bytes = strData.getBytes(StandardCharsets.UTF_8);
            final var length = bytes.length;
            writer.write(bytes, 0, length);
            writer.setLength(totalFileLength - pkIndexEntry.getLength() + length);
            entry.setPreviousByteSize(pkIndexEntry.getLength());
            final var updated = updateIndexValues(entry.getDatabaseName(), entry.getCollectionName(), entry.get_id(),
                    totalFileLength, length, page);
            return new UpdateResult(updated, compacted ? compactionFor(pkIndexEntry) : null);
        } finally {
            lock.unlock();
        }
    }

    private PkIndexEntry updateIndexValues(String dbName, String collectionName, String value, long position,
            int length, long page) throws IOException {
        final var newIndexEntry = new PkIndexEntry(dbName, collectionName, value, position, length, page);
        internalUpdatePKIndex(dbName, collectionName, value, newIndexEntry);
        return newIndexEntry;
    }

    private void bulkUpdateIndexValues(String dbName, String collName, List<PkIndexEntry> newEntries,
            LinkedHashMap<String, Long> oldLengths) throws IOException {
        final var indexFile = getIndexFile(dbName, collName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        final var newEntriesMap = newEntries.stream().collect(Collectors.toMap(PkIndexEntry::getValue, e -> e));
        final var lock = fileLock(indexFile).writeLock();
        lock.lock();
        try (var raf = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
            final int fileLength = (int) raf.length();
            byte[] buffer = new byte[fileLength];
            raf.readFully(buffer, 0, fileLength);
            final var lines = new String(buffer).split(Globals.NEWLINE_REGEX);
            long cumulativeShift = 0;
            final var updatedLines = new ArrayList<String>();
            for (final var line : lines) {
                if (line.isBlank())
                    continue;
                final var entry = PkIndexEntry.fromIndexFileEntry(dbName, collName, line);
                if (newEntriesMap.containsKey(entry.getValue())) {
                    updatedLines.add(newEntriesMap.get(entry.getValue()).toFileEntry());
                    cumulativeShift += oldLengths.get(entry.getValue());
                } else {
                    entry.setPosition(entry.getPosition() - cumulativeShift);
                    updatedLines.add(entry.toFileEntry());
                }
            }
            final var newContent = String.join(Globals.NEWLINE, updatedLines) + Globals.NEWLINE;
            final var newContentBytes = newContent.getBytes(StandardCharsets.UTF_8);
            raf.seek(0);
            raf.write(newContentBytes);
            raf.setLength(newContentBytes.length);
        } finally {
            lock.unlock();
        }
    }

    private void internalUpdatePKIndex(String dbName, String collectionName, String value, PkIndexEntry newPkIndexEntry)
            throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        final var lock = fileLock(indexFile).writeLock();
        lock.lock();
        try (var writer = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
            final int oldFileLength = (int) indexFile.length();
            byte[] buffer = new byte[oldFileLength];
            writer.readFully(buffer, 0, oldFileLength);
            final var wholeFile = new String(buffer);
            final var lines = wholeFile.split(Globals.NEWLINE_REGEX);
            var totalLengthBefore = 0;
            var indexLine = 0;
            var oldIndexLine = "";
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].isBlank()
                        && PkIndexEntry.fromIndexFileEntry(dbName, collectionName, lines[i]).getValue().equals(value)) {
                    indexLine = i;
                    oldIndexLine = lines[i];
                    break;
                } else {
                    totalLengthBefore += lines[i].length() + Globals.NEWLINE_CHAR_LENGTH;
                }
            }
            final var reIndexedEntries = reindexPks(oldIndexLine, newPkIndexEntry,
                    Arrays.stream(lines).skip(indexLine + 1).toList(), dbName, collectionName);
            final var reIndexedToWrite = reIndexedEntries.stream().map(PkIndexEntry::toFileEntry)
                    .collect(Collectors.joining(Globals.NEWLINE));
            writer.seek(totalLengthBefore);
            writer.write(reIndexedToWrite.getBytes(StandardCharsets.UTF_8), 0, reIndexedToWrite.length());
            int newFileSize;
            if (!reIndexedEntries.isEmpty()) {
                writer.writeBytes(Globals.NEWLINE);
                newFileSize = totalLengthBefore + reIndexedToWrite.length() + Globals.NEWLINE_CHAR_LENGTH;
            } else {
                newFileSize = totalLengthBefore + reIndexedToWrite.length();
            }
            writer.setLength(newFileSize);
        } finally {
            lock.unlock();
        }
    }

    private List<PkIndexEntry> reindexPks(String oldIndexEntryStr, PkIndexEntry newPkIndexEntry,
            List<String> restOfIndexesStr, String dbName, String collectionName) {
        final var oldIndexEntry = PkIndexEntry.fromIndexFileEntry(dbName, collectionName, oldIndexEntryStr);
        final var restOfIndexes = restOfIndexesStr.stream()
                .map(x -> PkIndexEntry.fromIndexFileEntry(dbName, collectionName, x)).collect(Collectors.toList());
        for (var index : restOfIndexes) {
            index.setPosition(index.getPosition() - oldIndexEntry.getLength());
        }
        if (newPkIndexEntry != null) {
            newPkIndexEntry.setPosition(newPkIndexEntry.getPosition() - oldIndexEntry.getLength());
            restOfIndexes.add(newPkIndexEntry);
        }
        return restOfIndexes;
    }

    public void writeIndexFile(String dbName, String collName, String fieldName,
            Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap) {
        for (var indexTypeList : indexEntryMap.entrySet()) {
            final var type = IndexKind.fileLabel(indexTypeList.getKey());
            final var indexFile = getIndexFile(dbName, collName, fieldName, type);
            final var lock = fileLock(indexFile).writeLock();
            lock.lock();
            try (var writer = new BufferedWriter(new FileWriter(indexFile, true), Globals.BUFFER_SIZE)) {
                var strData = indexTypeList.getValue().stream().map(FieldIndexEntry::toFileEntry)
                        .collect(Collectors.joining(Globals.NEWLINE));
                strData += Globals.NEWLINE;
                writer.append(strData);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                lock.unlock();
            }
        }
    }

    // Hash index counterpart of writeIndexFile: appends the element-match entries (value = hex hash)
    // for a single kind (object or array) to its own {coll}-{field}-Object.idx / -Array.idx file.
    public void writeHashIndexFile(String dbName, String collName, String fieldName, IndexKind kind,
            List<FieldIndexEntry<String>> entries) {
        if (entries.isEmpty()) {
            return;
        }
        final var indexFile = getIndexFile(dbName, collName, fieldName, kind.label());
        final var lock = fileLock(indexFile).writeLock();
        lock.lock();
        try (var writer = new BufferedWriter(new FileWriter(indexFile, true), Globals.BUFFER_SIZE)) {
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

    // Hash index counterpart of updateIndexFiles: removes then (re)writes a single hash entry in the
    // kind-specific index file. The entry value is already the hex hash, so getStringValue/
    // searchIndexValue locate it the same way as for scalar indexes.
    public void updateHashIndexFiles(String dbName, String collName, String fieldName, IndexKind kind,
            FieldIndexEntry<String> insertedEntry, FieldIndexEntry<String> removedEntry) throws IOException {
        final var indexFile = getIndexFile(dbName, collName, fieldName, kind.label());
        if (removedEntry != null) {
            final var lock = fileLock(indexFile).writeLock();
            lock.lock();
            try (var writer = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
                final var strWholeFile = readFully(writer);
                final var indexOfExisting = searchIndexValue(strWholeFile, removedEntry.getValue());
                if (indexOfExisting >= 0) {
                    shiftOtherEntries(writer, strWholeFile, indexOfExisting);
                    if (!removedEntry.getIds().isEmpty()) {
                        writer.writeBytes(removedEntry.toFileEntry());
                        writer.writeBytes(Globals.NEWLINE);
                    }
                }
            } finally {
                lock.unlock();
            }
        }
        if (insertedEntry != null) {
            final var lock = fileLock(indexFile).writeLock();
            lock.lock();
            try (var writer = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
                final var strWholeFile = readFully(writer);
                final var indexOfExisting = searchIndexValue(strWholeFile, insertedEntry.getValue());
                if (indexOfExisting >= 0) {
                    shiftOtherEntries(writer, strWholeFile, indexOfExisting);
                } else {
                    writer.seek(strWholeFile.length());
                }
                writer.writeBytes(insertedEntry.toFileEntry());
                writer.writeBytes(Globals.NEWLINE);
            } finally {
                lock.unlock();
            }
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

    public <T, K> void updateIndexFiles(String dbName, String collName, String fieldName,
            FieldIndexEntry<T> insertedEntry, FieldIndexEntry<K> removedEntry) throws IOException {
        if (removedEntry != null) {
            final var strIndexType = IndexKind.fileLabel(removedEntry.getValue().getClass());
            final var indexFile = getIndexFile(dbName, collName, fieldName, strIndexType);
            final var lock = fileLock(indexFile).writeLock();
            lock.lock();
            try (var writer = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
                final var strWholeFile = readFully(writer);
                final var strValue = getStringValue(removedEntry);
                final var indexOfExisting = searchIndexValue(strWholeFile, strValue);
                if (indexOfExisting >= 0) {
                    shiftOtherEntries(writer, strWholeFile, indexOfExisting);
                    if (!removedEntry.getIds().isEmpty()) {
                        final var toWriteLine = removedEntry.toFileEntry();
                        writer.writeBytes(toWriteLine);
                        writer.writeBytes(Globals.NEWLINE);
                    }
                }
            } finally {
                lock.unlock();
            }
        }
        if (insertedEntry != null) {
            final var strIndexType = IndexKind.fileLabel(insertedEntry.getValue().getClass());
            final var indexFile = getIndexFile(dbName, collName, fieldName, strIndexType);
            final var lock = fileLock(indexFile).writeLock();
            lock.lock();
            try (var writer = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
                final var strWholeFile = readFully(writer);
                final var strValue = getStringValue(insertedEntry);
                final var indexOfExisting = searchIndexValue(strWholeFile, strValue);
                final var toWriteLine = insertedEntry.toFileEntry();
                if (indexOfExisting >= 0) {
                    shiftOtherEntries(writer, strWholeFile, indexOfExisting);
                } else {
                    writer.seek(strWholeFile.length());
                }
                writer.writeBytes(toWriteLine);
                writer.writeBytes(Globals.NEWLINE);
            } finally {
                lock.unlock();
            }
        }
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
        return new String(buffer);
    }

    public boolean dropIndex(String dbName, String collName, String fieldName) {
        final var collFolder = getCollectionFolder(dbName, collName);
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

    public ConcurrentMap<String, List<FieldIndexEntry<?>>> readAllWholeFieldIndexFiles(String dbName, String collName,
            String fieldName) {
        final var collectionFolder = getCollectionFolder(dbName, collName);
        if (collectionFolder.exists()) {
            final var indexFiles = collectionFolder.listFiles((_, name) -> name.endsWith(Globals.INDEX_FILE_EXTENSION)
                    && !name.contains(Globals.PK_FIELD) && name.contains(fieldName));
            if (indexFiles != null) {
                return Arrays.stream(indexFiles).map(file -> {
                    final var lock = fileLock(file).readLock();
                    lock.lock();
                    try {
                        final var fileName = file.getName();
                        final var type = fileName.split("-")[2].split("\\.")[0];
                        return new AbstractMap.SimpleEntry<>(type, Files.readAllLines(file.toPath()));
                    } catch (IOException e) {
                        return null;
                    } finally {
                        lock.unlock();
                    }
                }).filter(Objects::nonNull)
                        .map((AbstractMap.SimpleEntry<String, List<String>> stringListSimpleEntry) -> {
                            final var className = stringListSimpleEntry.getKey();
                            final var clazz = ReflectionUtils.getClassFromSimpleName(className);
                            return new AbstractMap.SimpleEntry<>(className,
                                    stringListSimpleEntry.getValue().stream()
                                            .map(s -> FieldIndexEntry.fromIndexFileEntry(dbName, collName, s, clazz))
                                            .collect(Collectors.toList()));
                        }).collect(Collectors.toConcurrentMap(AbstractMap.SimpleEntry::getKey,
                                classListSimpleEntry -> new ArrayList<>(classListSimpleEntry.getValue())));
            }
        }
        return null;
    }

    public <T> List<FieldIndexEntry<T>> readWholeFieldIndexFiles(String dbName, String collName, String fieldName,
            Class<T> indexType) throws IOException {
        final var collectionFolder = getCollectionFolder(dbName, collName);
        if (collectionFolder.exists()) {
            final var strIndexType = IndexKind.fileLabel(indexType);
            final var indexFile = getIndexFile(dbName, collName, fieldName, strIndexType);
            if (indexFile.exists()) {
                final var lock = fileLock(indexFile).readLock();
                lock.lock();
                final List<String> lines;
                try {
                    lines = Files.readAllLines(indexFile.toPath());
                } finally {
                    lock.unlock();
                }
                return lines.stream().map(s -> FieldIndexEntry.fromIndexFileEntry(dbName, collName, s, indexType))
                        .sorted((o1, o2) -> switch ((Object) o1.getValue()) {
                            case Number n -> Double.compare(n.doubleValue(), ((Number) o2.getValue()).doubleValue());
                            case Boolean b -> Boolean.compare(b, (Boolean) o2.getValue());
                            case JsonCustom<?> c -> {
                                final var customClass = c.getClass();
                                //noinspection unchecked
                                yield customClass.cast(c).compare(customClass.cast(o2.getValue()).getCustomValue());
                            }
                            default -> ((String) o1.getValue()).compareToIgnoreCase((String) o2.getValue());
                        }).collect(Collectors.toList());
            }
        }
        return null;
    }

    // Hash index counterpart of readWholeFieldIndexFiles: loads every element-match entry (value =
    // hex hash) for the given kind, sorted by hash so SearchUtils' binary search works.
    public List<FieldIndexEntry<String>> readWholeHashIndexFile(String dbName, String collName, String fieldName,
            IndexKind kind) throws IOException {
        final var collectionFolder = getCollectionFolder(dbName, collName);
        if (collectionFolder.exists()) {
            final var indexFile = getIndexFile(dbName, collName, fieldName, kind.label());
            if (indexFile.exists()) {
                final var lock = fileLock(indexFile).readLock();
                lock.lock();
                final List<String> lines;
                try {
                    lines = Files.readAllLines(indexFile.toPath());
                } finally {
                    lock.unlock();
                }
                return lines.stream().filter(s -> !s.isBlank())
                        .map(s -> FieldIndexEntry.fromIndexFileEntry(dbName, collName, s, String.class))
                        .sorted(Comparator.comparing(FieldIndexEntry::getValue, String::compareToIgnoreCase))
                        .collect(Collectors.toList());
            }
        }
        return null;
    }

    public List<PkIndexEntry> readWholePkIndexFile(String dbName, String collectionName) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        if (!indexFile.exists()) {
            return new ArrayList<>();
        }
        final var entries = new ArrayList<PkIndexEntry>();
        final var keepLines = new ArrayList<String>();
        boolean dropped = false;
        final var lock = fileLock(indexFile).readLock();
        lock.lock();
        final List<String> indexLines;
        try {
            indexLines = Files.readAllLines(indexFile.toPath());
        } finally {
            lock.unlock();
        }
        for (var line : indexLines) {
            if (line.isEmpty())
                continue;
            try {
                entries.add(PkIndexEntry.fromIndexFileEntry(dbName, collectionName, line));
                keepLines.add(line);
            } catch (Exception e) {
                dropped = true;
                logger.warning("Removing malformed PK index entry in " + indexFile.getName() + ": " + e.getMessage());
            }
        }
        if (dropped) {
            rewriteFileAtomically(indexFile.toPath(), keepLines);
        }
        entries.sort(Comparator.comparing(PkIndexEntry::getValue));
        return entries;
    }

    public Map<String, DbEntry> readWholeCollectionPage(String dbName, String collectionName, long page)
            throws IOException {
        final var collectionFile = getCollectionFile(dbName, collectionName, page);
        if (!collectionFile.exists()) {
            return new HashMap<>();
        }
        final var result = new HashMap<String, DbEntry>();
        final var lock = fileLock(collectionFile).readLock();
        lock.lock();
        final List<String> pageLines;
        try {
            pageLines = Files.readAllLines(collectionFile.toPath());
        } finally {
            lock.unlock();
        }
        for (var line : pageLines) {
            if (line.isEmpty())
                continue;
            try {
                final var entry = DbEntry.fromString(dbName, collectionName, line);
                result.put(entry.get_id(), entry);
            } catch (Exception e) {
                // Skip-and-log only. We deliberately do NOT rewrite the .dat
                // file here: the .idx file stores byte offsets into the .dat,
                // so removing lines would invalidate every entry's recorded
                // position. Compacting both atomically is a separate concern
                // (a real compaction operation, not a read-side side effect).
                logger.warning("Skipping malformed entry in " + collectionFile.getName() + ": " + e.getMessage());
            }
        }
        return result;
    }

    private void rewriteFileAtomically(Path path, List<String> lines) throws IOException {
        final var tmp = path.resolveSibling(path.getFileName() + ".repair");
        Files.write(tmp, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // ATOMIC_MOVE can fail across filesystems or on platforms that don't
            // support it; fall back to a non-atomic move.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Stream<Map<String, DbEntry>> streamPages(String dbName, String collName) throws IOException {
        final var collectionFolder = getCollectionFolder(dbName, collName).toPath();
        if (!Files.exists(collectionFolder)) {
            return Stream.empty();
        }
        final var pathStream = Files.list(collectionFolder);
        return pathStream.filter(path -> path.toFile().getName().endsWith(Globals.DB_FILE_EXTENSION)).map(path -> {
            final var fileName = path.toFile().getName();
            final var fileParts = fileName.replace(Globals.DB_FILE_EXTENSION, "").split(Globals.FILE_PAGE_SEPARATOR);
            final var page = Long.parseLong(fileParts[fileParts.length - 1]);
            try {
                return readWholeCollectionPage(dbName, collName, page);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).onClose(pathStream::close);
    }

    public Stream<DbEntry> streamEntries(String dbName, String collName) throws IOException {
        return streamPages(dbName, collName).flatMap(map -> map.values().stream());
    }

    public PkIndexEntry findPkIndexEntry(String dbName, String collName, String id) throws IOException {
        final var indexFile = getIndexFile(dbName, collName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        if (!indexFile.exists()) {
            return null;
        }
        final var lock = fileLock(indexFile).readLock();
        lock.lock();
        final List<String> lines;
        try {
            lines = Files.readAllLines(indexFile.toPath());
        } finally {
            lock.unlock();
        }
        return lines.stream().filter(line -> !line.isEmpty())
                .map(line -> PkIndexEntry.fromIndexFileEntry(dbName, collName, line))
                .filter(entry -> entry.getValue().equals(id)).findFirst().orElse(null);
    }
}
