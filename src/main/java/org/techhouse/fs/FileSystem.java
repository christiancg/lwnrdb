package org.techhouse.fs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ex.DirectoryNotFoundException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.utils.JsonUtils;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class FileSystem {
    public static final String FILE_SEPARATOR = FileSystems.getDefault().getSeparator();
    public static final char INDEX_FILE_NAME_SEPARATOR = '-';
    private static final String RW_PERMISSIONS = "rwd";
    private final Gson gson = IocContainer.get(Gson.class);
    private final ExecutorService pool = Executors.newFixedThreadPool(Configuration.getInstance().getMaxFsThreads());
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

    public void createAdminDatabase()
            throws ExecutionException, InterruptedException {
        createDatabaseFolder(Globals.ADMIN_DB_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME);
        createCollectionFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
    }

    public boolean createDatabaseFolder(String dbName) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalCreateDatabaseFolder(dbName));
        return future.get();
    }

    public boolean deleteDatabase(String dbName) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalDeleteDatabase(dbName));
        return future.get();
    }

    private boolean internalCreateDatabaseFolder(String dbName) {
        final var dbFolder = new File(dbPath + FILE_SEPARATOR + dbName);
        if (!dbFolder.exists()) {
            return dbFolder.mkdir();
        }
        return true;
    }

    private boolean internalDeleteDatabase(String dbName) {
        final var dbFolder = new File(dbPath + FILE_SEPARATOR + dbName);
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
        return new File(dbPath + FILE_SEPARATOR + dbName + FILE_SEPARATOR + collectionName);
    }

    private File getCollectionFile(String dbName, String collectionName) {
        return new File(dbPath + FILE_SEPARATOR + dbName + FILE_SEPARATOR + collectionName + FILE_SEPARATOR
                + collectionName + Globals.DB_FILE_EXTENSION);
    }

    public boolean createCollectionFile(String dbName, String collectionName) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalCreateCollectionFile(dbName, collectionName));
        return future.get();
    }

    public boolean deleteCollectionFiles(String dbName, String collectionName) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalDropCollection(dbName, collectionName));
        return future.get();
    }

    private boolean internalCreateCollectionFile(String dbName, String collectionName) throws IOException {
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

    private boolean internalDropCollection(String dbName, String collectionName) {
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
        return new File(dbPath + FILE_SEPARATOR + dbName + FILE_SEPARATOR + collectionName + FILE_SEPARATOR
                + collectionName + INDEX_FILE_NAME_SEPARATOR + indexName + INDEX_FILE_NAME_SEPARATOR + indexType + Globals.INDEX_FILE_EXTENSION);
    }

    public DbEntry getById(PkIndexEntry pkIndexEntry) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalGetById(pkIndexEntry));
        return future.get();
    }

    private DbEntry internalGetById(PkIndexEntry pkIndexEntry) throws IOException {
        final var file = getCollectionFile(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName());
        try (final var reader = new RandomAccessFile(file, "r")) {
            reader.seek(pkIndexEntry.getPosition());
            final var entryLength = (int) pkIndexEntry.getLength();
            byte[] buffer = new byte[entryLength];
            reader.readFully(buffer, 0, entryLength);
            final var strEntry = new String(buffer);
            final var jsonObject = gson.fromJson(strEntry, JsonObject.class);
            final var entry = new DbEntry();
            entry.setDatabaseName(pkIndexEntry.getDatabaseName());
            entry.setCollectionName(pkIndexEntry.getCollectionName());
            entry.set_id(pkIndexEntry.getValue());
            jsonObject.remove(Globals.PK_FIELD);
            entry.setData(jsonObject);
            return entry;
        }
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalInsertIntoCollection(entry));
        return future.get();
    }

    private PkIndexEntry internalInsertIntoCollection(DbEntry entry) throws IOException {
        final var file = getCollectionFile(entry.getDatabaseName(), entry.getCollectionName());
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

    private PkIndexEntry indexNewPKValue(String dbName, String collectionName, String value, long position, int length) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        try (final var writer = new BufferedWriter(new FileWriter(indexFile, true), Globals.BUFFER_SIZE)) {
            final var indexEntry = new PkIndexEntry(dbName, collectionName, value, position, length);
            writer.append(indexEntry.toFileEntry());
            writer.newLine();
            return indexEntry;
        }
    }

    public void deleteFromCollection(PkIndexEntry pkIndexEntry) {
        pool.execute(() -> {
            try {
                internalDeleteFromCollection(pkIndexEntry);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void internalDeleteFromCollection(PkIndexEntry pkIndexEntry) throws IOException {
        final var file = getCollectionFile(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName());
        final int totalFileLength = (int) file.length();
        try (final var writer = new RandomAccessFile(file, RW_PERMISSIONS);
             FileChannel channel = writer.getChannel();
             FileLock lock = channel.lock()) {
            shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
            writer.setLength(totalFileLength - pkIndexEntry.getLength());
            deleteIndexValue(pkIndexEntry);
        }
    }

    private void deleteIndexValue(PkIndexEntry pkIndexEntry) throws IOException {
        internalUpdatePKIndex(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName(), pkIndexEntry.getValue(), null);
    }

    private void shiftOtherEntriesToStart(RandomAccessFile writer, PkIndexEntry pkIndexEntry, int totalFileLength) throws IOException {
        writer.seek(pkIndexEntry.getPosition() + pkIndexEntry.getLength());
        final int otherEntriesLength = (int) (totalFileLength - pkIndexEntry.getPosition() - pkIndexEntry.getLength());
        byte[] buffer = new byte[otherEntriesLength];
        writer.readFully(buffer, 0, otherEntriesLength);
        writer.seek(pkIndexEntry.getPosition());
        writer.write(buffer, 0, otherEntriesLength);
    }

    public PkIndexEntry updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalUpdateFromCollection(entry, pkIndexEntry));
        return future.get();
    }

    private PkIndexEntry internalUpdateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry) throws IOException {
        final var file = getCollectionFile(entry.getDatabaseName(), entry.getCollectionName());
        final int totalFileLength = (int) file.length();
        try (final var writer = new RandomAccessFile(file, RW_PERMISSIONS);
             FileChannel channel = writer.getChannel();
             FileLock lock = channel.lock()) {
            shiftOtherEntriesToStart(writer, pkIndexEntry, totalFileLength);
            writer.seek(totalFileLength - pkIndexEntry.getLength());
            final var strData = entry.toFileEntry();
            final var length = strData.length();
            writer.write(strData.getBytes(StandardCharsets.UTF_8), 0, length);
            writer.setLength(totalFileLength - pkIndexEntry.getLength() + strData.length());
            return updateIndexValues(entry.getDatabaseName(), entry.getCollectionName(), entry.get_id(), totalFileLength, length);
        }
    }

    private PkIndexEntry updateIndexValues(String dbName, String collectionName, String value, long position, int length) throws IOException {
        final var newIndexEntry = new PkIndexEntry(dbName, collectionName, value, position, length);
        internalUpdatePKIndex(dbName, collectionName, value, newIndexEntry);
        return newIndexEntry;
    }

    private void internalUpdatePKIndex(String dbName, String collectionName, String value, PkIndexEntry newPkIndexEntry) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        try (final var writer = new RandomAccessFile(indexFile, RW_PERMISSIONS);
             FileChannel channel = writer.getChannel();
             FileLock lock = channel.lock()) {
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
            final var reIndexedEntries = reindex(oldIndexLine, newPkIndexEntry, Arrays.stream(lines).skip(indexLine + 1).toList(), dbName, collectionName);
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

    private List<PkIndexEntry> reindex(String oldIndexEntryStr, PkIndexEntry newPkIndexEntry, List<String> restOfIndexesStr, String dbName, String collectionName) {
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
        pool.execute(() -> internalSaveIndexes(dbName, collName, fieldName, indexEntryMap));
    }

    private void internalSaveIndexes(String dbName, String collName, String fieldName, Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap) {
        for (var indexTypeList : indexEntryMap.entrySet()) {
            final var type = indexTypeList.getKey();
            final var parts = type.getName().split("\\.");
            final var actualType = parts[parts.length - 1];
            final var indexFile = getIndexFile(dbName, collName, fieldName, actualType);
            try (final var writer = new BufferedWriter(new FileWriter(indexFile, true), Globals.BUFFER_SIZE)) {
                final var strData = indexTypeList.getValue().stream().map(FieldIndexEntry::toFileEntry).collect(Collectors.joining("\n"));
                writer.append(strData);
                writer.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public boolean dropIndex(String dbName, String collName, String fieldName) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalDropIndex(dbName, collName, fieldName));
        return future.get();
    }

    private boolean internalDropIndex(String dbName, String collName, String fieldName) {
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

    public <T> List<FieldIndexEntry<T>> readWholeFieldIndexFiles(String dbName, String collName, String fieldName, Class<T> indexType)
            throws ExecutionException, InterruptedException {
        final Future<List<FieldIndexEntry<T>>> future = pool.submit(() -> internalReadWholeFieldIndexFiles(dbName, collName, fieldName, indexType));
        return future.get();
    }

    private <T> List<FieldIndexEntry<T>> internalReadWholeFieldIndexFiles(String dbName, String collectionName,
                                                                      String fieldName, Class<T> indexType)
            throws IOException {
        final var collectionFolder = getCollectionFolder(dbName, collectionName);
        if (collectionFolder.exists()) {
            final var strIndexType = JsonUtils.classAsString(indexType);
            final var indexFile = getIndexFile(dbName, collectionName, fieldName, strIndexType);
            if (indexFile.exists()) {
                return Files.readAllLines(indexFile.toPath()).stream().map(s ->
                        FieldIndexEntry.fromIndexFileEntry(dbName, collectionName, s, indexType))
                        .sorted((o1, o2) -> switch (o1.getValue()) {
                    case Double d -> Double.compare(d, (Double) o2.getValue());
                    case Boolean b -> Boolean.compare(b, (Boolean) o2.getValue());
                    default -> ((String) o1.getValue()).compareToIgnoreCase((String) o2.getValue());
                }).collect(Collectors.toList());
            }
        }
        return null;
    }

    public List<PkIndexEntry> readWholePkIndexFile(String dbName, String collectionName) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalReadWholePkIndexFile(dbName, collectionName));
        return future.get();
    }

    private List<PkIndexEntry> internalReadWholePkIndexFile(String dbName, String collectionName) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, Globals.PK_FIELD, Globals.PK_FIELD_TYPE);
        if (indexFile.exists()) {
            return Files.readAllLines(indexFile.toPath()).stream().map(s -> PkIndexEntry.fromIndexFileEntry(dbName, collectionName, s))
                    .sorted(Comparator.comparing(PkIndexEntry::getValue)).collect(Collectors.toList());
        } else {
            return new ArrayList<>();
        }
    }

    public Map<String, DbEntry> readWholeCollection(String dbName, String collectionName) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalReadWholeCollectionFile(dbName, collectionName));
        return future.get();
    }

    public Map<String, DbEntry> readWholeCollection(String collectionIdentifier) throws ExecutionException, InterruptedException {
        final var parts = collectionIdentifier.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
        final var future = pool.submit(() -> internalReadWholeCollectionFile(parts[0], parts[1]));
        return future.get();
    }

    private Map<String, DbEntry> internalReadWholeCollectionFile(String dbName, String collectionName) throws IOException {
        final var collectionFile = getCollectionFile(dbName, collectionName);
        if (collectionFile.exists()) {
            return Arrays.stream(Files.readString(collectionFile.toPath()).split("(?=(?<!:)\\{)")).map(s -> DbEntry.fromString(dbName, collectionName, s))
                    .collect(Collectors.toMap(DbEntry::get_id, dbEntry -> dbEntry));
        } else {
            return new HashMap<>();
        }
    }
}
