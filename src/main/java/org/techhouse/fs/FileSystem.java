package org.techhouse.fs;

import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.DirectoryNotFoundException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.utils.JsonUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

public class FileSystem {
    public static final char INDEX_FILE_NAME_SEPARATOR = '-';
    private static final String RW_PERMISSIONS = "rwd";
    private final EJson eJson = IocContainer.get(EJson.class);
    private final Map<String, Semaphore> locks = new ConcurrentHashMap<>();
    private String dbPath;

    private void lock(String dbName, String collName) throws InterruptedException {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var lock = locks.get(collIdentifier);
        if (lock == null) {
            final var newLock = new Semaphore(1);
            newLock.acquire();
            locks.put(collIdentifier, newLock);
        } else {
            lock.acquire();
        }
    }

    private void release(String collIdentifier) {
        final var lock = locks.get(collIdentifier);
        if (lock != null) {
            lock.release();
        }
    }

    private void release(String dbName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        release(collIdentifier);
    }

    private void removeLock(String dbName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        locks.remove(collIdentifier);
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
    }

    public boolean createDatabaseFolder(String dbName) {
        final var dbFolder = new File(dbPath + Globals.FILE_SEPARATOR + dbName);
        if (!dbFolder.exists()) {
            return dbFolder.mkdir();
        }
        return true;
    }

    public boolean deleteDatabase(String dbName) throws InterruptedException {
        final var dbFolder = new File(dbPath + Globals.FILE_SEPARATOR + dbName);
        final var fileDeletionResult = new ArrayList<Boolean>();
        if (dbFolder.exists()) {
            final var dbFolders = dbFolder.listFiles();
            if (dbFolders != null) {
                for (var collFolder : dbFolders) {
                    final var collName = collFolder.getName();
                    lock(dbName, collName);
                    final var collFiles = collFolder.listFiles();
                    if (collFiles != null) {
                        for (var file : collFiles) {
                            fileDeletionResult.add(file.delete());
                        }
                        fileDeletionResult.add(collFolder.delete());
                    }
                    release(dbName, collName);
                    removeLock(dbName, collName);
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

    private File getCollectionFile(String dbName, String collectionName) {
        return new File(dbPath + Globals.FILE_SEPARATOR + dbName + Globals.FILE_SEPARATOR + collectionName +
                Globals.FILE_SEPARATOR + collectionName + Globals.DB_FILE_EXTENSION);
    }

    public boolean createCollectionFile(String dbName, String collectionName)
            throws IOException {
        final var collectionFile = getCollectionFile(dbName, collectionName);
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

    public boolean deleteCollectionFiles(String dbName, String collectionName)
            throws InterruptedException {
        lock(dbName, collectionName);
        final var collectionFile = getCollectionFile(dbName, collectionName);
        final var collectionFolder = new File(collectionFile.getParent());
        final var fileDeletionResult = new ArrayList<Boolean>();
        if (collectionFolder.exists()) {
            for (var file : Objects.requireNonNull(collectionFolder.listFiles())) {
                fileDeletionResult.add(file.delete());
            }
            fileDeletionResult.add(collectionFolder.delete());
            release(dbName, collectionName);
            removeLock(dbName, collectionName);
            return fileDeletionResult.stream().allMatch(aBoolean -> aBoolean);
        }
        release(dbName, collectionName);
        return false;
    }

    private File getIndexFile(String dbName, String collectionName, String indexName, String indexType) {
        return new File(dbPath + Globals.FILE_SEPARATOR + dbName + Globals.FILE_SEPARATOR + collectionName +
                Globals.FILE_SEPARATOR + collectionName + INDEX_FILE_NAME_SEPARATOR + indexName +
                INDEX_FILE_NAME_SEPARATOR + indexType + Globals.INDEX_FILE_EXTENSION);
    }

    public DbEntry getById(PkIndexEntry pkIndexEntry) throws Exception {
        final var file = getCollectionFile(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName());
        try (final var reader = new RandomAccessFile(file, "r")) {
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
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry) throws IOException, InterruptedException {
        final var dbName = entry.getDatabaseName();
        final var collName = entry.getCollectionName();
        lock(dbName, collName);
        final var file = getCollectionFile(entry.getDatabaseName(), entry.getCollectionName());
        try (final var writer = new BufferedWriter(new FileWriter(file, true), Globals.BUFFER_SIZE)) {
            final var strData = entry.toFileEntry();
            final var length = strData.length();
            final var totalFileLength = file.length();
            writer.append(strData);
            writer.flush();
            final var entryId = entry.get_id();
            return indexNewPKValue(entry.getDatabaseName(), entry.getCollectionName(), entryId, totalFileLength, length);
        } finally {
            release(dbName, collName);
        }
    }

    private PkIndexEntry indexNewPKValue(String dbName, String collectionName, String value, long position, int length)
            throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        try (final var writer = new BufferedWriter(new FileWriter(indexFile, true), Globals.BUFFER_SIZE)) {
            final var indexEntry = new PkIndexEntry(dbName, collectionName, value, position, length);
            writer.append(indexEntry.toFileEntry());
            writer.newLine();
            return indexEntry;
        }
    }

    public void deleteFromCollection(PkIndexEntry pkIndexEntry) throws InterruptedException {
        final var dbName = pkIndexEntry.getDatabaseName();
        final var collName = pkIndexEntry.getCollectionName();
        lock(dbName, collName);
        final var file = getCollectionFile(dbName, collName);
        final int totalFileLength = (int) file.length();
        try (final var writer = new RandomAccessFile(file, RW_PERMISSIONS)) {
            shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
            writer.setLength(totalFileLength - pkIndexEntry.getLength());
            deleteIndexValue(pkIndexEntry);
        } catch (IOException e) {
            release(dbName, collName);
            throw new RuntimeException(e);
        }
        release(dbName, collName);
    }

    private void deleteIndexValue(PkIndexEntry pkIndexEntry) throws IOException {
        internalUpdatePKIndex(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName(), pkIndexEntry.getValue(),
                null);
    }

    private void shiftOtherEntriesToStart(RandomAccessFile writer, PkIndexEntry pkIndexEntry, int totalFileLength)
            throws IOException {
        writer.seek(pkIndexEntry.getPosition() + pkIndexEntry.getLength());
        final int otherEntriesLength = (int) (totalFileLength - pkIndexEntry.getPosition() - pkIndexEntry.getLength());
        byte[] buffer = new byte[otherEntriesLength];
        writer.readFully(buffer, 0, otherEntriesLength);
        writer.seek(pkIndexEntry.getPosition());
        writer.write(buffer, 0, otherEntriesLength);
    }

    public PkIndexEntry updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry)
            throws IOException, InterruptedException {
        final var dbName = entry.getDatabaseName();
        final var collName = entry.getCollectionName();
        lock(dbName, collName);
        final var file = getCollectionFile(dbName, collName);
        final int totalFileLength = (int) file.length();
        try (final var writer = new RandomAccessFile(file, RW_PERMISSIONS)) {
            shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
            writer.seek(totalFileLength - pkIndexEntry.getLength());
            final var strData = entry.toFileEntry();
            final var length = strData.length();
            writer.write(strData.getBytes(StandardCharsets.UTF_8), 0, length);
            writer.setLength(totalFileLength - pkIndexEntry.getLength() + strData.length());
            return updateIndexValues(entry.getDatabaseName(), entry.getCollectionName(), entry.get_id(), totalFileLength, length);
        } finally {
            release(dbName, collName);
        }
    }

    private PkIndexEntry updateIndexValues(String dbName, String collectionName, String value, long position, int length)
            throws IOException {
        final var newIndexEntry = new PkIndexEntry(dbName, collectionName, value, position, length);
        internalUpdatePKIndex(dbName, collectionName, value, newIndexEntry);
        return newIndexEntry;
    }

    private void internalUpdatePKIndex(String dbName, String collectionName, String value, PkIndexEntry newPkIndexEntry)
            throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        try (final var writer = new RandomAccessFile(indexFile, RW_PERMISSIONS)) {
            final int oldFileLength = (int) indexFile.length();
            byte[] buffer = new byte[oldFileLength];
            writer.readFully(buffer, 0, oldFileLength);
            final var wholeFile = new String(buffer);
            final var lines = wholeFile.split("\\n");
            var totalLengthBefore = 0;
            var indexLine = 0;
            var oldIndexLine = "";
            for (int i = 0; i < lines.length; i++) {
                final var parts = lines[i].split(Globals.INDEX_ENTRY_SEPARATOR_REGEX);
                if (parts[0].equals(value)) {
                    indexLine = i;
                    oldIndexLine = lines[i];
                    break;
                } else {
                    totalLengthBefore += lines[i].length() + 1; // +1 -> the newline character
                }
            }
            final var reIndexedEntries = reindexPks(oldIndexLine, newPkIndexEntry, Arrays.stream(lines).skip(indexLine + 1).toList(), dbName, collectionName);
            final var reIndexedToWrite = reIndexedEntries.stream().map(PkIndexEntry::toFileEntry).collect(Collectors.joining("\n"));
            writer.seek(totalLengthBefore);
            writer.write(reIndexedToWrite.getBytes(StandardCharsets.UTF_8), 0, reIndexedToWrite.length());
            var newFileSize = 0;
            if (!reIndexedEntries.isEmpty()) {
                writer.write('\n');
                newFileSize = totalLengthBefore + reIndexedToWrite.length() + 1;
            } else {
                newFileSize = totalLengthBefore + reIndexedToWrite.length();
            }
            writer.setLength(newFileSize);
        }
    }

    private List<PkIndexEntry> reindexPks(String oldIndexEntryStr, PkIndexEntry newPkIndexEntry,
                                       List<String> restOfIndexesStr, String dbName, String collectionName) {
        final var oldIndexEntry = PkIndexEntry.fromIndexFileEntry(dbName, collectionName, oldIndexEntryStr);
        final var restOfIndexes = restOfIndexesStr.stream().map(x -> PkIndexEntry.fromIndexFileEntry(dbName, collectionName, x)).collect(Collectors.toList());
        for (var index : restOfIndexes) {
            index.setPosition(index.getPosition() - oldIndexEntry.getLength());
        }
        if (newPkIndexEntry != null) {
            newPkIndexEntry.setPosition(newPkIndexEntry.getPosition() - oldIndexEntry.getLength());
            restOfIndexes.add(newPkIndexEntry);
        }
        return restOfIndexes;
    }

    public void writeIndexFile(String dbName, String collName, String fieldName, Map<Class<?>,
            List<FieldIndexEntry<?>>> indexEntryMap) {
        for (var indexTypeList : indexEntryMap.entrySet()) {
            final var type = indexTypeList.getKey();
            final var parts = type.getName().split("\\.");
            final var actualType = parts[parts.length - 1];
            final var indexFile = getIndexFile(dbName, collName, fieldName, actualType);
            try (final var writer = new BufferedWriter(new FileWriter(indexFile, true), Globals.BUFFER_SIZE)) {
                var strData = indexTypeList.getValue().stream().map(FieldIndexEntry::toFileEntry).collect(Collectors.joining("\n"));
                strData += "\n";
                writer.append(strData);
                writer.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public <T, K> void updateIndexFiles(String dbName, String collName, String fieldName,
                                    FieldIndexEntry<T> insertedEntry, FieldIndexEntry<K> removedEntry)
            throws IOException {
        if (removedEntry != null) {
            final var clazz = removedEntry.getValue().getClass();
            final var strIndexType = JsonUtils.classAsString(clazz);
            final var indexFile = getIndexFile(dbName, collName, fieldName, strIndexType);
            try (final var writer = new RandomAccessFile(indexFile, RW_PERMISSIONS)) {
                final var strWholeFile = readFully(writer);
                final var indexOfExisting = strWholeFile.indexOf(removedEntry.getValue().toString() + Globals.INDEX_ENTRY_SEPARATOR);
                if (indexOfExisting >= 0) {
                    shiftOtherEntries(writer, strWholeFile, indexOfExisting);
                    if (!removedEntry.getIds().isEmpty()) {
                        final var toWriteLine = removedEntry.toFileEntry().getBytes(StandardCharsets.UTF_8);
                        writer.write(toWriteLine);
                        writer.write('\n');
                    }
                }
            }
        }
        if (insertedEntry != null) {
            final var clazz = insertedEntry.getValue().getClass();
            final var strIndexType = JsonUtils.classAsString(clazz);
            final var indexFile = getIndexFile(dbName, collName, fieldName, strIndexType);
            try (final var writer = new RandomAccessFile(indexFile, RW_PERMISSIONS)) {
                final var strWholeFile = readFully(writer);
                final var indexOfExisting = strWholeFile.indexOf(insertedEntry.getValue().toString() + Globals.INDEX_ENTRY_SEPARATOR);
                final var toWriteLine = insertedEntry.toFileEntry().getBytes(StandardCharsets.UTF_8);
                if (indexOfExisting >= 0) {
                    shiftOtherEntries(writer, strWholeFile, indexOfExisting);
                    writer.write(toWriteLine);
                    writer.write('\n');
                } else {
                    writer.seek(strWholeFile.length());
                    writer.write(toWriteLine);
                    writer.write('\n');
                }
            }
        }
    }

    private void shiftOtherEntries(RandomAccessFile writer, String strWholeFile, int indexOfExisting)
            throws IOException {
        final var newlineIndex = strWholeFile.indexOf('\n', indexOfExisting);
        var otherEntries = strWholeFile.substring(newlineIndex);
        var charsToRemove = -1;
        if (otherEntries.startsWith("\n")) {
            otherEntries = otherEntries.substring(1);
            charsToRemove++;
        }
        if (otherEntries.endsWith("\n")) {
            otherEntries = otherEntries.substring(0, otherEntries.length() - 1);
            charsToRemove++;
        }
        if (!otherEntries.isEmpty()) {
            writer.seek(indexOfExisting);
            var newNewLineIndex = strWholeFile.length() - (newlineIndex - indexOfExisting) - charsToRemove;
            writer.write(otherEntries.getBytes(StandardCharsets.UTF_8));
            writer.write('\n');
            writer.seek(newNewLineIndex);
            writer.setLength(newNewLineIndex);
        } else {
            writer.seek(indexOfExisting);
            writer.setLength(indexOfExisting);
        }
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
            final var indexFiles = collFolder.listFiles((dir, name) -> name.endsWith(Globals.INDEX_FILE_EXTENSION)
                    && name.contains(INDEX_FILE_NAME_SEPARATOR + fieldName + INDEX_FILE_NAME_SEPARATOR));
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

    public <T> List<FieldIndexEntry<T>> readWholeFieldIndexFiles(String dbName, String collName, String fieldName,
                                                                 Class<T> indexType) throws IOException {
        final var collectionFolder = getCollectionFolder(dbName, collName);
        if (collectionFolder.exists()) {
            final var strIndexType = JsonUtils.classAsString(indexType);
            final var indexFile = getIndexFile(dbName, collName, fieldName, strIndexType);
            if (indexFile.exists()) {
                return Files.readAllLines(indexFile.toPath()).stream().map(s ->
                                FieldIndexEntry.fromIndexFileEntry(dbName, collName, s, indexType))
                        .sorted((o1, o2) -> switch (o1.getValue()) {
                            case Double d -> Double.compare(d, (Double) o2.getValue());
                            case Boolean b -> Boolean.compare(b, (Boolean) o2.getValue());
                            default -> ((String) o1.getValue()).compareToIgnoreCase((String) o2.getValue());
                        }).collect(Collectors.toList());
            }
        }
        return null;
    }

    public List<PkIndexEntry> readWholePkIndexFile(String dbName, String collectionName) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        if (indexFile.exists()) {
            return Files.readAllLines(indexFile.toPath()).stream().map(s -> PkIndexEntry.fromIndexFileEntry(dbName, collectionName, s))
                    .sorted(Comparator.comparing(PkIndexEntry::getValue)).collect(Collectors.toList());
        } else {
            return new ArrayList<>();
        }
    }

    public Map<String, DbEntry> readWholeCollection(String dbName, String collectionName) throws IOException {
        final var collectionFile = getCollectionFile(dbName, collectionName);
        if (collectionFile.exists()) {
            return Arrays.stream(Files.readString(collectionFile.toPath()).split("(?=(?<!:)\\{)")).map(s -> {
                        try {
                            return DbEntry.fromString(dbName, collectionName, s);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toMap(DbEntry::get_id, dbEntry -> dbEntry));
        } else {
            return new HashMap<>();
        }
    }

    public Map<String, DbEntry> readWholeCollection(String collectionIdentifier) throws IOException {
        final var parts = collectionIdentifier.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
        final var dbName = parts[0];
        final var collName = parts[1];
        return readWholeCollection(dbName, collName);
    }
}
