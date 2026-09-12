package org.techhouse.fs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

final class DocumentPageStore {
    private static final Logger logger = Logger.logFor(DocumentPageStore.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    private final FilePaths paths;

    DocumentPageStore(FilePaths paths) {
        this.paths = paths;
    }

    DbEntry getById(PkIndexEntry pkIndexEntry) throws IOException {
        final var file = paths.collectionPage(pkIndexEntry.getDatabaseName(), pkIndexEntry.getCollectionName(),
                pkIndexEntry.getPage());
        final var lock = FileLocks.lockFor(file).readLock();
        lock.lock();
        try (var reader = new RandomAccessFile(file, Globals.R_PERMISSIONS)) {
            return readEntryFromOpenFile(reader, pkIndexEntry);
        } finally {
            lock.unlock();
        }
    }

    List<DbEntry> getByIndexEntries(List<PkIndexEntry> entries) throws IOException {
        final var result = new ArrayList<DbEntry>();
        if (entries == null || entries.isEmpty()) {
            return result;
        }
        final var byPage = entries.stream().collect(Collectors.groupingBy(PkIndexEntry::getPage));
        for (var pageGroup : byPage.entrySet()) {
            final var first = pageGroup.getValue().getFirst();
            final var file = paths.collectionPage(first.getDatabaseName(), first.getCollectionName(),
                    pageGroup.getKey());
            final var pageEntries = pageGroup.getValue().stream()
                    .sorted(Comparator.comparingLong(PkIndexEntry::getPosition)).toList();
            final var lock = FileLocks.lockFor(file).readLock();
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

    Map<String, DbEntry> readWholeCollectionPage(String dbName, String collectionName, long page) throws IOException {
        final var collectionFile = paths.collectionPage(dbName, collectionName, page);
        if (!collectionFile.exists()) {
            return new HashMap<>();
        }
        final var result = new HashMap<String, DbEntry>();
        for (var line : FileLocks.readAllLinesLocked(collectionFile)) {
            if (line.isEmpty())
                continue;
            try {
                final var entry = DbEntry.fromString(dbName, collectionName, line);
                result.put(entry.get_id(), entry);
            } catch (Exception e) {
                // Skip-and-log only: the .idx files store byte offsets into this .dat, so dropping a
                // line here would invalidate every later entry's recorded position.
                logger.warning("Skipping malformed entry in " + collectionFile.getName() + ": " + e.getMessage());
            }
        }
        return result;
    }

    Stream<Map<String, DbEntry>> streamPages(String dbName, String collName) throws IOException {
        final var collectionFolder = paths.collectionFolder(dbName, collName).toPath();
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

    Stream<DbEntry> streamEntries(String dbName, String collName) throws IOException {
        return streamPages(dbName, collName).flatMap(map -> map.values().stream());
    }

    DbEntry readEntryFromOpenFile(RandomAccessFile reader, PkIndexEntry pkIndexEntry) throws IOException {
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
        entry.setData(jsonObject);
        return entry;
    }
}
