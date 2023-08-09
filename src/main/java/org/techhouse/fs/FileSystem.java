package org.techhouse.fs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexEntry;
import org.techhouse.ex.DirectoryNotFoundException;
import org.techhouse.ioc.IocContainer;

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
import java.util.stream.Collectors;

public class FileSystem {
    public static final String FILE_SEPARATOR = FileSystems.getDefault().getSeparator();
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
            for(var collFolder : Objects.requireNonNull(dbFolder.listFiles())) {
                for (var file : Objects.requireNonNull(collFolder.listFiles())) {
                    fileDeletionResult.add(file.delete());
                }
                fileDeletionResult.add(collFolder.delete());
            }
            fileDeletionResult.add(dbFolder.delete());
            return fileDeletionResult.stream().allMatch(aBoolean -> aBoolean);
        }
        return false;
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

    private File getIndexFile(String dbName, String collectionName, String indexName) {
        return new File(dbPath + FILE_SEPARATOR + dbName + FILE_SEPARATOR + collectionName + FILE_SEPARATOR
                + collectionName + '-' + indexName + Globals.INDEX_FILE_EXTENSION);
    }

    public DbEntry getById(IndexEntry indexEntry) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalGetById(indexEntry));
        return future.get();
    }

    private DbEntry internalGetById(IndexEntry indexEntry) throws IOException {
        final var file = getCollectionFile(indexEntry.getDatabaseName(), indexEntry.getCollectionName());
        try (final var reader = new RandomAccessFile(file, "r")){
            reader.seek(indexEntry.getPosition());
            final var entryLength = (int)indexEntry.getLength();
            byte[] buffer = new byte[entryLength];
            reader.readFully(buffer, 0, entryLength);
            final var strEntry = new String(buffer);
            final var jsonObject = gson.fromJson(strEntry, JsonObject.class);
            final var entry = new DbEntry();
            entry.setDatabaseName(indexEntry.getDatabaseName());
            entry.setCollectionName(indexEntry.getCollectionName());
            entry.set_id(indexEntry.getValue());
            jsonObject.remove(Globals.PK_FIELD);
            entry.setData(jsonObject);
            return entry;
        }
    }

    public IndexEntry insertIntoCollection(DbEntry entry) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalInsertIntoCollection(entry));
        return future.get();
    }

    private IndexEntry internalInsertIntoCollection(DbEntry entry) throws IOException {
        final var file = getCollectionFile(entry.getDatabaseName(), entry.getCollectionName());
        try (final var writer = new BufferedWriter(new FileWriter(file, true), Globals.BUFFER_SIZE)) {
            final var strData = entry.toFileEntry();
            final var length = strData.length();
            final var totalFileLength = file.length();
            writer.append(strData);
            writer.flush();
            final var entryId = entry.get_id();
            return indexNewPKValue(entry.getDatabaseName(), entry.getCollectionName(), entryId, entryId, totalFileLength, length);
        }
    }

    private IndexEntry indexNewPKValue(String dbName, String collectionName, String value, String entryId, long position, int length) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, Globals.PK_FIELD);
        try (final var writer = new BufferedWriter(new FileWriter(indexFile, true), Globals.BUFFER_SIZE)) {
            final var indexEntry = new IndexEntry(dbName, collectionName, value, Set.of(entryId), position, length);
            writer.append(indexEntry.toFileEntry());
            writer.newLine();
            return indexEntry;
        }
    }

    public void deleteFromCollection(IndexEntry idIndexEntry) {
        pool.execute(() -> {
            try {
                internalDeleteFromCollection(idIndexEntry);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void internalDeleteFromCollection(IndexEntry idIndexEntry) throws IOException {
        final var file = getCollectionFile(idIndexEntry.getDatabaseName(), idIndexEntry.getCollectionName());
        final int totalFileLength = (int) file.length();
        try (final var writer = new RandomAccessFile(file, RW_PERMISSIONS);
             FileChannel channel = writer.getChannel();
             FileLock lock = channel.lock()) {
            shiftOtherEntriesToStart(writer, idIndexEntry, totalFileLength);
            writer.setLength(totalFileLength - idIndexEntry.getLength());
            deleteIndexValue(idIndexEntry);
        }
    }

    private void deleteIndexValue(IndexEntry idIndexEntry) throws IOException {
        internalUpdatePKIndex(idIndexEntry.getDatabaseName(), idIndexEntry.getCollectionName(), idIndexEntry.getValue(), null);
    }

    private void shiftOtherEntriesToStart(RandomAccessFile writer, IndexEntry idIndexEntry, int totalFileLength) throws IOException {
        writer.seek(idIndexEntry.getPosition() + idIndexEntry.getLength());
        final int otherEntriesLength = (int) (totalFileLength - idIndexEntry.getPosition() - idIndexEntry.getLength());
        byte[] buffer = new byte[otherEntriesLength];
        writer.readFully(buffer, 0, otherEntriesLength);
        writer.seek(idIndexEntry.getPosition());
        writer.write(buffer, 0, otherEntriesLength);
    }

    public IndexEntry updateFromCollection(DbEntry entry, IndexEntry idIndexEntry) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalUpdateFromCollection(entry, idIndexEntry));
        return future.get();
    }

    private IndexEntry internalUpdateFromCollection(DbEntry entry, IndexEntry idIndexEntry) throws IOException {
        final var file = getCollectionFile(entry.getDatabaseName(), entry.getCollectionName());
        final int totalFileLength = (int) file.length();
        try (final var writer = new RandomAccessFile(file, RW_PERMISSIONS);
             FileChannel channel = writer.getChannel();
             FileLock lock = channel.lock()) {
            shiftOtherEntriesToStart(writer, idIndexEntry, totalFileLength);
            writer.seek(totalFileLength - idIndexEntry.getLength());
            final var strData = entry.toFileEntry();
            final var length = strData.length();
            writer.write(strData.getBytes(StandardCharsets.UTF_8),0, length);
            writer.setLength(totalFileLength - idIndexEntry.getLength() + strData.length());
            return updateIndexValues(entry.getDatabaseName(), entry.getCollectionName(), entry.get_id(), totalFileLength, length);
        }
    }

    private IndexEntry updateIndexValues(String dbName, String collectionName, String value, long position, int length) throws IOException {
        final var newIndexEntry = new IndexEntry(dbName, collectionName, value, Set.of(value), position, length);
        internalUpdatePKIndex(dbName, collectionName, value, newIndexEntry);
        return newIndexEntry;
    }

    private void internalUpdatePKIndex(String dbName, String collectionName, String value, IndexEntry newIndexEntry) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, Globals.PK_FIELD);
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
            for (int i=0; i < lines.length; i++) {
                final var parts = lines[i].split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
                if (parts[0].equals(value)) {
                    indexLine = i;
                    oldIndexLine = lines[i];
                    break;
                } else {
                    totalLengthBefore += lines[i].length() + 1; // +1 -> the newline character
                }
            }
            final var reIndexedEntries = reindex(oldIndexLine, newIndexEntry, Arrays.stream(lines).skip(indexLine + 1).toList(), dbName, collectionName);
            final var reIndexedToWrite = reIndexedEntries.stream().map(IndexEntry::toFileEntry).collect(Collectors.joining("\n"));
            writer.seek(totalLengthBefore);
            writer.write(reIndexedToWrite.getBytes(StandardCharsets.UTF_8), 0, reIndexedToWrite.length());
            writer.write('\n');
            writer.setLength(totalLengthBefore + reIndexedToWrite.length() + 1);
        }
    }

    private List<IndexEntry> reindex(String oldIndexEntryStr, IndexEntry newIndexEntry, List<String> restOfIndexesStr, String dbName, String collectionName) {
        final var oldIndexEntry = IndexEntry.fromIndexFileEntry(dbName, collectionName, oldIndexEntryStr);
        final var restOfIndexes = restOfIndexesStr.stream().map(x -> IndexEntry.fromIndexFileEntry(dbName, collectionName, x)).collect(Collectors.toList());
        for (var index: restOfIndexes) {
            index.setPosition(index.getPosition() - oldIndexEntry.getLength());
        }
        if (newIndexEntry != null) {
            newIndexEntry.setPosition(newIndexEntry.getPosition() - oldIndexEntry.getLength());
            restOfIndexes.add(newIndexEntry);
        }
        return restOfIndexes;
    }

    public List<IndexEntry> readWholeIndexFile(String collectionIdentifier, String indexName) throws ExecutionException, InterruptedException {
        final var parts = collectionIdentifier.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
        final var future = pool.submit(() -> internalReadWholeIndexFile(parts[0], parts[1], indexName));
        return future.get();
    }

    public List<IndexEntry> readWholeIndexFile(String dbName, String collectionName, String indexName) throws ExecutionException, InterruptedException {
        final var future = pool.submit(() -> internalReadWholeIndexFile(dbName, collectionName, indexName));
        return future.get();
    }

    private List<IndexEntry> internalReadWholeIndexFile(String dbName, String collectionName, String indexName) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, indexName);
        if (indexFile.exists()) {
            return Files.readAllLines(indexFile.toPath()).stream().map(s -> IndexEntry.fromIndexFileEntry(dbName, collectionName, s))
                    .sorted(Comparator.comparing(IndexEntry::getValue)).collect(Collectors.toList());
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
