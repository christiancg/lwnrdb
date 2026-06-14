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
import java.util.stream.Stream;

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
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME);
        final var pagesDatabases = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME);
        final var pagesCollections = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        final var pagesUsers = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
        final var pagesCollectionUsage = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME);
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
        return new File(dbPath + Globals.FILE_SEPARATOR + dbName + Globals.FILE_SEPARATOR + collectionName +
                Globals.FILE_SEPARATOR + collectionName + Globals.FILE_PAGE_SEPARATOR + page + Globals.DB_FILE_EXTENSION);
    }

    public boolean createCollectionFile(String dbName, String collectionName)
            throws IOException {
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
        return new File(dbPath + Globals.FILE_SEPARATOR + dbName + Globals.FILE_SEPARATOR + collectionName +
                Globals.FILE_SEPARATOR + collectionName + Globals.INDEX_FILE_NAME_SEPARATOR + indexName +
                Globals.INDEX_FILE_NAME_SEPARATOR + indexType + Globals.INDEX_FILE_EXTENSION);
    }

    public DbEntry getById(PkIndexEntry pkIndexEntry) throws Exception {
        final var file = getCollectionFile(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName(), pkIndexEntry.getPage());
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

    public <T extends DbEntry> List<IndexedDbEntry> bulkInsertIntoCollection(final String dbName, final String collName, final List<T> entries) throws IOException {
        final var indexEntries = new ArrayList<IndexedDbEntry>();
        final var pkEntriesToIndex = new ArrayList<PkIndexEntry>();
        final var entrySet = entries.stream().collect(Collectors.groupingBy(DbEntry::getPage)).entrySet();
        for (var groupedEntry : entrySet) {
            final var page = groupedEntry.getKey();
            final var pageEntries = groupedEntry.getValue();
            final var file = getCollectionFile(dbName, collName, page);
            var currentOffset = file.length();
            try (final var writer = new BufferedWriter(new FileWriter(file, true), Globals.BUFFER_SIZE)) {
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
            }
        }
        bulkIndexNewPKValues(dbName, collName, pkEntriesToIndex);
        return indexEntries;
    }

    private void bulkIndexNewPKValues(String dbName, String collName, List<PkIndexEntry> pkEntries) throws IOException {
        final var indexFile = getIndexFile(dbName, collName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        try (final var writer = new BufferedWriter(new FileWriter(indexFile, true), Globals.BUFFER_SIZE)) {
            for (var pkEntry : pkEntries) {
                writer.append(pkEntry.toFileEntry());
                writer.newLine();
            }
        }
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry) throws IOException {
        final var dbName = entry.getDatabaseName();
        final var collName = entry.getCollectionName();
        final var page = entry.getPage();
        final var file = getCollectionFile(dbName, collName, page);
        try (final var writer = new BufferedWriter(new FileWriter(file, true), Globals.BUFFER_SIZE)) {
            final var strData = entry.toFileEntry() + Globals.NEWLINE;
            final var bytes = strData.getBytes(StandardCharsets.UTF_8);
            final var length = bytes.length;
            var totalFileLength = file.length();
            writer.append(strData);
            final var entryId = entry.get_id();
            return indexNewPKValue(entry.getDatabaseName(), entry.getCollectionName(), entryId, totalFileLength, length, page);
        }
    }

    private PkIndexEntry indexNewPKValue(String dbName, String collectionName, String value, long position, int length, long page)
            throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        try (final var writer = new BufferedWriter(new FileWriter(indexFile, true), Globals.BUFFER_SIZE)) {
            final var indexEntry = new PkIndexEntry(dbName, collectionName, value, position, length, page);
            writer.append(indexEntry.toFileEntry());
            writer.newLine();
            return indexEntry;
        }
    }

    public void deleteFromCollection(PkIndexEntry pkIndexEntry) {
        final var dbName = pkIndexEntry.getDatabaseName();
        final var collName = pkIndexEntry.getCollectionName();
        final var page = pkIndexEntry.getPage();
        final var file = getCollectionFile(dbName, collName, page);
        final long totalFileLength = file.length();
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

    private void shiftOtherEntriesToStart(RandomAccessFile writer, PkIndexEntry pkIndexEntry, long totalFileLength)
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
        final var result = new ArrayList<IndexedDbEntry>();
        final var entrySet = entries.stream().collect(Collectors.groupingBy(indexedDbEntry -> indexedDbEntry.getIndex().getPage())).entrySet();
        for (var groupedEntry : entrySet) {
            final var page = groupedEntry.getKey();
            final var pageEntries = groupedEntry.getValue();
            final var file = getCollectionFile(dbName, collName, page);
            long totalFileLength = file.length();
            final var newPkEntries = new ArrayList<PkIndexEntry>();
            final var oldLengths = new LinkedHashMap<String, Long>();
            try (final var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
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
                    final var newPkEntry = new PkIndexEntry(dbName, collName, entry.get_id(), newPosition, length, page);
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
            }
            bulkUpdateIndexValues(dbName, collName, newPkEntries, oldLengths);
        }
        return result;
    }

    public PkIndexEntry updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry)
            throws IOException {
        final var dbName = entry.getDatabaseName();
        final var collName = entry.getCollectionName();
        final var page = entry.getPage();
        final var file = getCollectionFile(dbName, collName, page);
        final long totalFileLength = file.length();
        try (final var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
            writer.seek(totalFileLength - pkIndexEntry.getLength());
            final var strData = entry.toFileEntry() + Globals.NEWLINE;
            final var bytes = strData.getBytes(StandardCharsets.UTF_8);
            final var length = bytes.length;
            writer.write(bytes, 0, length);
            writer.setLength(totalFileLength - pkIndexEntry.getLength() + length);
            entry.setPreviousByteSize(pkIndexEntry.getLength());
            return updateIndexValues(entry.getDatabaseName(), entry.getCollectionName(), entry.get_id(), totalFileLength, length, page);
        }
    }

    private PkIndexEntry updateIndexValues(String dbName, String collectionName, String value, long position, int length, long page)
            throws IOException {
        final var newIndexEntry = new PkIndexEntry(dbName, collectionName, value, position, length, page);
        internalUpdatePKIndex(dbName, collectionName, value, newIndexEntry);
        return newIndexEntry;
    }

    private void bulkUpdateIndexValues(String dbName, String collName,
                                       List<PkIndexEntry> newEntries,
                                       LinkedHashMap<String, Long> oldLengths) throws IOException {
        final var indexFile = getIndexFile(dbName, collName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        final var newEntriesMap = newEntries.stream().collect(Collectors.toMap(PkIndexEntry::getValue, e -> e));
        try (final var raf = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
            final int fileLength = (int) raf.length();
            byte[] buffer = new byte[fileLength];
            raf.readFully(buffer, 0, fileLength);
            final var lines = new String(buffer).split(Globals.NEWLINE_REGEX);
            long cumulativeShift = 0;
            final var updatedLines = new ArrayList<String>();
            for (final var line : lines) {
                if (line.isBlank()) continue;
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
        }
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
                if (!lines[i].isBlank() && PkIndexEntry.fromIndexFileEntry(dbName, collectionName, lines[i]).getValue().equals(value)) {
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
            } catch (IOException e) {
                throw new RuntimeException(e);
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
            if (lineEnd == -1) break;
            lineStart = lineEnd + Globals.NEWLINE_CHAR_LENGTH;
        }
        return -1;
    }

    private <K> String getStringValue(FieldIndexEntry<K> entry) {
        final var value = entry.getValue();
        var strValue = "";
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
                                        FieldIndexEntry<T> insertedEntry, FieldIndexEntry<K> removedEntry)
            throws IOException {
        if (removedEntry != null) {
            final var clazz = removedEntry.getValue().getClass();
            final var strIndexType = Number.class.isAssignableFrom(clazz) ? Number.class.getSimpleName() : clazz.getSimpleName();
            final var indexFile = getIndexFile(dbName, collName, fieldName, strIndexType);
            try (final var writer = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
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
            }
        }
        if (insertedEntry != null) {
            final var clazz = insertedEntry.getValue().getClass();
            final var strIndexType = Number.class.isAssignableFrom(clazz) ? Number.class.getSimpleName() : clazz.getSimpleName();
            final var indexFile = getIndexFile(dbName, collName, fieldName, strIndexType);
            try (final var writer = new RandomAccessFile(indexFile, Globals.RW_PERMISSIONS)) {
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
            final var indexFiles = collFolder.listFiles((_, name) -> name.endsWith(Globals.INDEX_FILE_EXTENSION)
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
            final var indexFiles = collectionFolder.listFiles((_, name) -> name.endsWith(Globals.INDEX_FILE_EXTENSION)
                    && !name.contains(Globals.PK_FIELD)
                    && name.contains(fieldName));
            if (indexFiles != null) {
                return Arrays.stream(indexFiles).map(file -> {
                    try {
                        final var fileName = file.getName();
                        final var type = fileName.split("-")[2].split("\\.")[0];
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
                        .sorted((o1, o2) -> switch ((Object) o1.getValue()) {
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

    public Map<String, DbEntry> readWholeCollectionPage(String dbName, String collectionName, long page) throws IOException {
        final var collectionFile = getCollectionFile(dbName, collectionName, page);
        if (collectionFile.exists()) {
            return Files.readAllLines(collectionFile.toPath())
                    .stream()
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
                        try {
                            return DbEntry.fromString(dbName, collectionName, s);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toMap(DbEntry::get_id, dbEntry -> dbEntry, (_, replacement) -> replacement));
        } else {
            return new HashMap<>();
        }
    }

    public Stream<Map<String, DbEntry>> streamPages(String dbName, String collName) throws IOException {
        final var collectionFolder = getCollectionFolder(dbName, collName).toPath();
        if (!Files.exists(collectionFolder)) {
            return Stream.empty();
        }
        final var pathStream = Files.list(collectionFolder);
        return pathStream
                .filter(path -> path.toFile().getName().endsWith(Globals.DB_FILE_EXTENSION))
                .map(path -> {
                    final var fileName = path.toFile().getName();
                    final var fileParts = fileName.replace(Globals.DB_FILE_EXTENSION, "")
                            .split(Globals.FILE_PAGE_SEPARATOR);
                    final var page = Long.parseLong(fileParts[fileParts.length - 1]);
                    try {
                        return readWholeCollectionPage(dbName, collName, page);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .onClose(pathStream::close);
    }

    public PkIndexEntry findPkIndexEntry(String dbName, String collName, String id) throws IOException {
        final var indexFile = getIndexFile(dbName, collName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        if (!indexFile.exists()) {
            return null;
        }
        return Files.readAllLines(indexFile.toPath()).stream()
                .filter(line -> !line.isEmpty())
                .map(line -> PkIndexEntry.fromIndexFileEntry(dbName, collName, line))
                .filter(entry -> entry.getValue().equals(id))
                .findFirst()
                .orElse(null);
    }
}
