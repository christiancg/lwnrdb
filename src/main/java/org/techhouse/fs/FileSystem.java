package org.techhouse.fs;

import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.DirectoryNotFoundException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.utils.ReflectionUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public class FileSystem {
    private final EJson eJson = IocContainer.get(EJson.class);
    private String dbPath;

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

    public boolean deleteCollectionFiles(String dbName, String collectionName) {
        final var collectionFile = getCollectionFile(dbName, collectionName);
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
        return new File(dbPath + Globals.FILE_SEPARATOR + dbName + Globals.FILE_SEPARATOR + collectionName +
                Globals.FILE_SEPARATOR + collectionName + Globals.INDEX_FILE_NAME_SEPARATOR + indexName +
                Globals.INDEX_FILE_NAME_SEPARATOR + indexType + Globals.INDEX_FILE_EXTENSION);
    }

    public DbEntry getById(PkIndexEntry pkIndexEntry) throws Exception {
        final var file = getCollectionFile(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName());
        try (final var reader = new RandomAccessFile(file, Globals.R_PERMISSIONS)) {
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

    public List<IndexedDbEntry> bulkInsertIntoCollection(final String dbName, final String collName, final List<DbEntry> entries) throws IOException {
        final var file = getCollectionFile(dbName, collName);
        final var indexEntries = new ArrayList<IndexedDbEntry>();
        try (final var writer = new BufferedWriter(new FileWriter(file, true), Globals.BUFFER_SIZE)) {
            for (var entry : entries) {
                final var strData = entry.toFileEntry();
                final var length = strData.length();
                final var totalFileLength = file.length();
                writer.append(strData);
                writer.flush();
                final var entryId = entry.get_id();
                final var indexEntry = indexNewPKValue(dbName, collName, entryId, totalFileLength, length);
                final var indexedEntry = new IndexedDbEntry();
                indexedEntry.setIndex(indexEntry);
                indexedEntry.setCollectionName(collName);
                indexedEntry.setDatabaseName(dbName);
                indexedEntry.set_id(entryId);
                indexedEntry.setData(entry.getData());
                indexEntries.add(indexedEntry);
            }
        }
        return indexEntries;
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry) throws IOException {
        final var dbName = entry.getDatabaseName();
        final var collName = entry.getCollectionName();
        final var file = getCollectionFile(dbName, collName);
        try (final var writer = new BufferedWriter(new FileWriter(file, true), Globals.BUFFER_SIZE)) {
            final var strData = entry.toFileEntry();
            final var length = strData.length();
            final var totalFileLength = file.length();
            writer.append(strData);
            writer.flush();
            final var entryId = entry.get_id();
            return indexNewPKValue(entry.getDatabaseName(), entry.getCollectionName(), entryId, totalFileLength, length);
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

    public void deleteFromCollection(PkIndexEntry pkIndexEntry) {
        final var dbName = pkIndexEntry.getDatabaseName();
        final var collName = pkIndexEntry.getCollectionName();
        final var file = getCollectionFile(dbName, collName);
        final int totalFileLength = (int) file.length();
        try (final var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
            writer.setLength(totalFileLength - pkIndexEntry.getLength());
            deleteIndexValue(pkIndexEntry);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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

    public List<IndexedDbEntry> bulkUpdateFromCollection(String dbName, String collName, List<IndexedDbEntry> entries)
            throws IOException {
        final var file = getCollectionFile(dbName, collName);
        int totalFileLength = (int) file.length();
        final var result = new ArrayList<IndexedDbEntry>();
        try (final var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            for (var entry : entries) {
                shiftOtherEntriesToStart(writer, entry.getIndex(), totalFileLength);
                writer.seek(totalFileLength - entry.getIndex().getLength());
                final var strData = entry.toFileEntry();
                final var length = strData.length();
                writer.write(strData.getBytes(StandardCharsets.UTF_8), 0, length);
                writer.setLength(totalFileLength - entry.getIndex().getLength() + strData.length());
                final var updatedIndex = updateIndexValues(dbName, collName, entry.get_id(), totalFileLength, length);
                final var updatedIndexEntry = new IndexedDbEntry();
                updatedIndexEntry.setIndex(updatedIndex);
                updatedIndexEntry.set_id(entry.get_id());
                updatedIndexEntry.setCollectionName(collName);
                updatedIndexEntry.setDatabaseName(dbName);
                updatedIndexEntry.setData(entry.getData());
                result.add(updatedIndexEntry);
                totalFileLength = (int) file.length();
            }
        }
        return result;
    }

    public PkIndexEntry updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry)
            throws IOException {
        final var dbName = entry.getDatabaseName();
        final var collName = entry.getCollectionName();
        final var file = getCollectionFile(dbName, collName);
        final int totalFileLength = (int) file.length();
        try (final var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
            writer.seek(totalFileLength - pkIndexEntry.getLength());
            final var strData = entry.toFileEntry();
            final var length = strData.length();
            writer.write(strData.getBytes(StandardCharsets.UTF_8), 0, length);
            writer.setLength(totalFileLength - pkIndexEntry.getLength() + strData.length());
            return updateIndexValues(entry.getDatabaseName(), entry.getCollectionName(), entry.get_id(), totalFileLength, length);
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
        try (final var writer = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
            final int oldFileLength = (int) indexFile.length();
            byte[] buffer = new byte[oldFileLength];
            writer.readFully(buffer, 0, oldFileLength);
            final var wholeFile = new String(buffer);
            final var lines = wholeFile.split(Globals.NEWLINE_REGEX);
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
                    totalLengthBefore += lines[i].length() + Globals.NEWLINE_CHAR_LENGTH;
                }
            }
            final var reIndexedEntries = reindexPks(oldIndexLine, newPkIndexEntry, Arrays.stream(lines).skip(indexLine + 1).toList(), dbName, collectionName);
            final var reIndexedToWrite = reIndexedEntries.stream().map(PkIndexEntry::toFileEntry).collect(Collectors.joining(Globals.NEWLINE));
            writer.seek(totalLengthBefore);
            writer.write(reIndexedToWrite.getBytes(StandardCharsets.UTF_8), 0, reIndexedToWrite.length());
            var newFileSize = 0;
            if (!reIndexedEntries.isEmpty()) {
                writer.writeBytes(Globals.NEWLINE);
                newFileSize = totalLengthBefore + reIndexedToWrite.length() + Globals.NEWLINE_CHAR_LENGTH;
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
            final var type = indexTypeList.getKey().getSimpleName();
            final var indexFile = getIndexFile(dbName, collName, fieldName, type);
            try (final var writer = new BufferedWriter(new FileWriter(indexFile, true), Globals.BUFFER_SIZE)) {
                var strData = indexTypeList.getValue().stream().map(FieldIndexEntry::toFileEntry).collect(Collectors.joining(Globals.NEWLINE));
                strData += Globals.NEWLINE;
                writer.append(strData);
                writer.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private int searchIndexValue(String strWholeFile, String value) {
        if (strWholeFile.startsWith(value + Globals.INDEX_ENTRY_SEPARATOR)) {
            return 0;
        } else {
            return strWholeFile.indexOf(Globals.NEWLINE + value + Globals.INDEX_ENTRY_SEPARATOR);
        }
    }

    public <T, K> void updateIndexFiles(String dbName, String collName, String fieldName,
                                        FieldIndexEntry<T> insertedEntry, FieldIndexEntry<K> removedEntry)
            throws IOException {
        if (removedEntry != null) {
            final var clazz = removedEntry.getValue().getClass();
            final var strIndexType = Number.class.isAssignableFrom(clazz) ? Number.class.getSimpleName() : clazz.getSimpleName();
            final var indexFile = getIndexFile(dbName, collName, fieldName, strIndexType);
            try (final var writer = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
                final var strWholeFile = readFully(writer);
                final var strValue = removedEntry.getValue() instanceof JsonCustom<?> ?
                        ((JsonCustom<?>)removedEntry.getValue()).getValue() :
                        removedEntry.getValue().toString();
                final var indexOfExisting = searchIndexValue(strWholeFile, strValue);
                if (indexOfExisting >= 0) {
                    final var otherEntriesLength = shiftOtherEntries(writer, strWholeFile, indexOfExisting);
                    if (!removedEntry.getIds().isEmpty()) {
                        final var toWriteLine = removedEntry.toFileEntry();
                        writer.writeBytes(toWriteLine);
                        writer.writeBytes(Globals.NEWLINE);
                    } else if (otherEntriesLength == 0) {
                        final var indexOfNewline = strWholeFile.indexOf(Globals.NEWLINE, indexOfExisting);
                        final var newFileLength = strWholeFile.length() - (indexOfNewline - indexOfExisting) - Globals.NEWLINE_CHAR_LENGTH;
                        writer.setLength(newFileLength);
                    }
                }
            }
        }
        if (insertedEntry != null) {
            final var clazz = insertedEntry.getValue().getClass();
            final var strIndexType = Number.class.isAssignableFrom(clazz) ? Number.class.getSimpleName() : clazz.getSimpleName();
            final var indexFile = getIndexFile(dbName, collName, fieldName, strIndexType);
            try (final var writer = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
                final var strWholeFile = readFully(writer);
                final var strValue = insertedEntry.getValue() instanceof JsonCustom<?> ?
                        ((JsonCustom<?>)insertedEntry.getValue()).getValue() :
                        insertedEntry.getValue().toString();
                final var indexOfExisting = searchIndexValue(strWholeFile, strValue);
                final var toWriteLine = insertedEntry.toFileEntry();
                if (indexOfExisting >= 0) {
                    shiftOtherEntries(writer, strWholeFile, indexOfExisting);
                } else {
                    writer.seek(strWholeFile.length());
                }
                writer.writeBytes(toWriteLine);
                writer.writeBytes(Globals.NEWLINE);
            }
        }
    }

    private Integer shiftOtherEntries(RandomAccessFile writer, String strWholeFile, int indexOfExisting)
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
        return otherEntries.length();
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
                    && name.contains(Globals.INDEX_FILE_NAME_SEPARATOR + fieldName + Globals.INDEX_FILE_NAME_SEPARATOR));
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

    public ConcurrentMap<String, List<FieldIndexEntry<?>>> readAllWholeFieldIndexFiles(String dbName, String collName, String fieldName) {
        final var collectionFolder = getCollectionFolder(dbName, collName);
        if (collectionFolder.exists()) {
            final var indexFiles = collectionFolder.listFiles((dir, name) -> name.endsWith(Globals.INDEX_FILE_EXTENSION)
                    && !name.contains(Globals.PK_FIELD)
                    && name.contains(fieldName));
            if (indexFiles != null) {
                return Arrays.stream(indexFiles).map(file -> {
                    try {
                        final var fileName = file.getName();
                        final var type = fileName.split("-")[2];
                        return new AbstractMap.SimpleEntry<>(type, Files.readAllLines(file.toPath()));
                    } catch (IOException e) {
                        return null;
                    }
                }).filter(Objects::nonNull).map((AbstractMap.SimpleEntry<String, List<String>> stringListSimpleEntry) -> {
                    final var className = stringListSimpleEntry.getKey();
                    final var clazz = ReflectionUtils.getClassFromSimpleName(className);
                    return new AbstractMap.SimpleEntry<>(className,
                            stringListSimpleEntry.getValue().stream()
                                    .map(s -> FieldIndexEntry.fromIndexFileEntry(dbName, collName, s, clazz))
                                    .collect(Collectors.toList()));
                }).collect(Collectors.toConcurrentMap(AbstractMap.SimpleEntry::getKey,
                        classListSimpleEntry -> new ArrayList<>(classListSimpleEntry.getValue()))
                );
            }
        }
        return null;
    }

    public <T> List<FieldIndexEntry<T>> readWholeFieldIndexFiles(String dbName, String collName, String fieldName,
                                                                 Class<T> indexType) throws IOException {
        final var collectionFolder = getCollectionFolder(dbName, collName);
        if (collectionFolder.exists()) {
            final var strIndexType = Number.class.isAssignableFrom(indexType) ? Number.class.getSimpleName() : indexType.getSimpleName();
            final var indexFile = getIndexFile(dbName, collName, fieldName, strIndexType);
            if (indexFile.exists()) {
                return Files.readAllLines(indexFile.toPath()).stream().map(s ->
                                FieldIndexEntry.fromIndexFileEntry(dbName, collName, s, indexType))
                        .sorted((o1, o2) -> switch (o1.getValue()) {
                            case Number n -> Double.compare(n.doubleValue(), ((Number)o2.getValue()).doubleValue());
                            case Boolean b -> Boolean.compare(b, (Boolean) o2.getValue());
                            case JsonCustom<?> c -> {
                                final var customClass = c.getClass();
                                yield customClass.cast(c).compare(customClass.cast(o2.getValue()).getCustomValue());
                            }
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
            return Arrays.stream(Files.readString(collectionFile.toPath()).split(Globals.READ_WHOLE_COLLECTION_REGEX))
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
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
