package org.techhouse.fs;

import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexEntry;
import org.techhouse.ex.DirectoryNotFoundException;

import java.io.*;

public class FileSystem {
    private static final int BUFFER_SIZE = 32768;
    private static final String DB_FILE_EXTENSION = ".dat";
    private static final String INDEX_FILE_EXTENSION = ".idx";
    private static final String ID_FIELD_NAME = "_id";

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
        return new File(dbPath + "/" + dbName + '/' + collectionName + DB_FILE_EXTENSION);
    }

    public boolean createCollectionFile(String dbName, String collectionName) throws IOException {
        final var dbFile = getCollectionFile(dbName, collectionName);
        if (!dbFile.exists()) {
            return dbFile.createNewFile();
        }
        return true;
    }

    private File getIndexFile(String dbName, String collectionName, String indexName) {
        return new File(dbPath + "/" + dbName + '/' + collectionName + "-" + indexName + INDEX_FILE_EXTENSION);
    }

    public boolean createDatabaseIndexFile(String dbName, String collectionName, String indexName) throws IOException {
        final var dbIndexFile = getIndexFile(dbName, collectionName, indexName);
        if (!dbIndexFile.exists()) {
            return dbIndexFile.createNewFile();
        }
        return true;
    }

    public String insertIntoCollection(DbEntry entry) throws IOException {
        final var file = getCollectionFile(entry.getDatabaseName(), entry.getCollectionName());
        final var writer = new BufferedWriter(new FileWriter(file), BUFFER_SIZE);
        final var strData = entry.toFileEntry();
        final var length = strData.length();
        final var totalFileLength = file.length();
        writer.append(strData);
        writer.flush();
        writer.close();
        indexNewValue(entry.getDatabaseName(), entry.getCollectionName(), ID_FIELD_NAME, entry.get_id(), totalFileLength, length);
        return entry.get_id();
    }

    private void indexNewValue(String dbName, String collectionName, String indexedField, String value, long position, int length) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, indexedField);
        final var writer = new BufferedWriter(new FileWriter(indexFile), BUFFER_SIZE);
        final var indexEntry = new IndexEntry(dbName, collectionName, value, position, length);
        writer.append(indexEntry.toFileEntry());
        writer.newLine();
        writer.flush();
        writer.close();
    }

    public void updateFromCollection(DbEntry entry, IndexEntry idIndexEntry) throws IOException {
        final var file = getCollectionFile(entry.getDatabaseName(), entry.getCollectionName());
        final var writer = new RandomAccessFile(file, "rwd");
        writer.seek(idIndexEntry.getPosition() + idIndexEntry.getLength());
        final var restOfFile = writer.readUTF();
        writer.seek(idIndexEntry.getPosition());
        writer.writeUTF(restOfFile);
        final var totalFileLength = file.length();
        writer.seek(file.length() - idIndexEntry.getLength());
        final var strData = entry.toFileEntry();
        final var length = strData.length();
        writer.writeUTF(strData);
        writer.setLength(totalFileLength - idIndexEntry.getLength() + strData.length());
        writer.close();
        updateIndexValues(entry.getDatabaseName(), entry.getCollectionName(), ID_FIELD_NAME, entry.get_id(), totalFileLength, length);
    }

    private void updateIndexValues(String dbName, String collectionName, String indexName, String value, long position, int length) throws IOException {
        final var indexFile = getIndexFile(dbName, collectionName, indexName);
        final var writer = new RandomAccessFile(indexFile, "rwd");
        final var wholeFile = writer.readUTF();
        final var lines = wholeFile.split("\\n");
        final var totalFileLength = wholeFile.length();
        var totalLengthBefore = 0;
        var lineLength = 0;
        for (String line : lines) {
            final var parts = line.split("\\|");
            if (parts[0].equals(value)) {
                lineLength = line.length();
                break;
            } else {
                totalLengthBefore += line.length() + 1; // +1 -> the newline character
            }
        }
        writer.seek(totalLengthBefore + lineLength + 1);// +1 -> the newline character
        final var restOfFile = writer.readUTF();
        writer.seek(totalLengthBefore + 1);// +1 -> the newline character
        writer.writeUTF(restOfFile);
        writer.seek(totalFileLength - lineLength);
        final var newIndexEntry = new IndexEntry(dbName, collectionName, value, position, length);
        final var strIndexEntry = newIndexEntry.toFileEntry();
        writer.writeUTF(strIndexEntry);
        writer.writeUTF("\n");
        writer.setLength(totalFileLength - lineLength + strIndexEntry.length() + 1);
        writer.close();
    }
}
