package org.techhouse.fs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexEntry;
import org.techhouse.ex.DirectoryNotFoundException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class FileSystem {
    private static final int BUFFER_SIZE = 32768;
    private static final String DB_FILE_EXTENSION = ".dat";
    private static final String INDEX_FILE_EXTENSION = ".idx";
    private static final String ID_FIELD_NAME = "_id";
    private static final Gson gson = new Gson();

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

    public boolean createDatabaseFolder(String dbName) {
        final var dbFolder = new File(dbPath + "/" + dbName);
        if (!dbFolder.exists()) {
            return dbFolder.mkdir();
        }
        return true;
    }

    private File getCollectionFile(String dbName, String collectionName) {
        return new File(dbPath + "/" + dbName + '/' + collectionName + '/' + collectionName + DB_FILE_EXTENSION);
    }

    public boolean createCollectionFile(String dbName, String collectionName) throws IOException {
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

    private File getIndexFile(String dbName, String collectionName, String indexName) {
        return new File(dbPath + "/" + dbName + '/' + collectionName + '/' + collectionName + "-" + indexName + INDEX_FILE_EXTENSION);
    }

    public DbEntry getById(IndexEntry indexEntry) throws IOException {
        final var file = getCollectionFile(indexEntry.getDatabaseName(), indexEntry.getCollectionName());
        final var reader = new RandomAccessFile(file, "r");
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
        jsonObject.remove(ID_FIELD_NAME);
        entry.setData(jsonObject);
        reader.close();
        return entry;
    }

    public IndexEntry insertIntoCollection(DbEntry entry) throws IOException {
        final var file = getCollectionFile(entry.getDatabaseName(), entry.getCollectionName());
        final var writer = new BufferedWriter(new FileWriter(file, true), BUFFER_SIZE);
        final var strData = entry.toFileEntry();
        final var length = strData.length();
        final var totalFileLength = file.length();
        writer.append(strData);
        writer.flush();
        writer.close();
        return indexNewValue(entry.getDatabaseName(), entry.getCollectionName(), ID_FIELD_NAME, entry.get_id(), totalFileLength, length);
    }

    private IndexEntry indexNewValue(String dbName, String collectionName, String indexedField, String value, long position, int length) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, indexedField);
        final var writer = new BufferedWriter(new FileWriter(indexFile, true), BUFFER_SIZE);
        final var indexEntry = new IndexEntry(dbName, collectionName, value, position, length);
        writer.append(indexEntry.toFileEntry());
        writer.newLine();
        writer.flush();
        writer.close();
        return indexEntry;
    }

    public void deleteFromCollection(IndexEntry idIndexEntry) throws IOException {
        final var file = getCollectionFile(idIndexEntry.getDatabaseName(), idIndexEntry.getCollectionName());
        final int totalFileLength = (int) file.length();
        final var writer = new RandomAccessFile(file, "rwd");
        shiftOtherEntriesToStart(writer, idIndexEntry, totalFileLength);
        writer.setLength(totalFileLength - idIndexEntry.getLength());
        writer.close();
        deleteIndexValue(idIndexEntry);
    }

    private void deleteIndexValue(IndexEntry idIndexEntry) throws IOException {
        internalUpdateIndex(idIndexEntry.getDatabaseName(), idIndexEntry.getCollectionName(), ID_FIELD_NAME, idIndexEntry.getValue(), null);
    }

    private void shiftOtherEntriesToStart(RandomAccessFile writer, IndexEntry idIndexEntry, int totalFileLength) throws IOException {
        writer.seek(idIndexEntry.getPosition() + idIndexEntry.getLength());
        final int otherEntriesLength = (int) (totalFileLength - idIndexEntry.getPosition() - idIndexEntry.getLength());
        byte[] buffer = new byte[otherEntriesLength];
        writer.readFully(buffer, 0, otherEntriesLength);
        writer.seek(idIndexEntry.getPosition());
        writer.write(buffer, 0, otherEntriesLength);
    }


    public IndexEntry updateFromCollection(DbEntry entry, IndexEntry idIndexEntry) throws IOException {
        final var file = getCollectionFile(entry.getDatabaseName(), entry.getCollectionName());
        final int totalFileLength = (int) file.length();
        final var writer = new RandomAccessFile(file, "rwd");
        shiftOtherEntriesToStart(writer, idIndexEntry, totalFileLength);
        writer.seek(totalFileLength - idIndexEntry.getLength());
        final var strData = entry.toFileEntry();
        final var length = strData.length();
        writer.write(strData.getBytes(StandardCharsets.UTF_8),0, length);
        writer.setLength(totalFileLength - idIndexEntry.getLength() + strData.length());
        writer.close();
        return updateIndexValues(entry.getDatabaseName(), entry.getCollectionName(), ID_FIELD_NAME, entry.get_id(), totalFileLength, length);
    }

    private IndexEntry updateIndexValues(String dbName, String collectionName, String indexName, String value, long position, int length) throws IOException {
        final var newIndexEntry = new IndexEntry(dbName, collectionName, value, position, length);
        internalUpdateIndex(dbName, collectionName, indexName, value, newIndexEntry);
        return newIndexEntry;
    }

    private void internalUpdateIndex(String dbName, String collectionName, String indexName, String value, IndexEntry newIndexEntry) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, indexName);
        final var writer = new RandomAccessFile(indexFile, "rwd");
        final int oldFileLength = (int) indexFile.length();
        byte[] buffer = new byte[oldFileLength];
        writer.readFully(buffer, 0, oldFileLength);
        final var wholeFile = new String(buffer);
        final var lines = wholeFile.split("\\n");
        var totalLengthBefore = 0;
        var indexLine = 0;
        var oldIndexLine = "";
        for (int i=0; i < lines.length; i++) {
            final var parts = lines[i].split("\\|");
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
        writer.close();
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

    public List<IndexEntry> readWholeIndexFile(String dbName, String collectionName, String indexName) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, indexName);
        if (indexFile.exists()) {
            return Files.readAllLines(indexFile.toPath()).stream().map(s -> IndexEntry.fromIndexFileEntry(dbName, collectionName, s))
                    .sorted(Comparator.comparing(IndexEntry::getValue)).collect(Collectors.toList());
        } else {
            return new ArrayList<>();
        }
    }
}
