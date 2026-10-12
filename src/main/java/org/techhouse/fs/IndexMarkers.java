package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;
import org.techhouse.utils.OwnerOnlyPermissions;

public final class IndexMarkers {
    private static final String BUILDING_SUFFIX = ".building";
    private static final String DIRTY_SUFFIX = "-indexes.dirty";
    private static final String UNCLEAN_STOP_SUFFIX = "-indexes.unclean";
    private static final Logger logger = Logger.logFor(IndexMarkers.class);

    private final FilePaths paths;
    private final Set<String> marked = ConcurrentHashMap.newKeySet();
    private final ReentrantLock loadLock = new ReentrantLock();
    private volatile String loadedFor;

    IndexMarkers(FilePaths paths) {
        this.paths = paths;
    }

    public void mark(String dbName, String collName, String fieldName) throws IOException {
        ensureLoaded();
        final var marker = buildingFile(dbName, collName, fieldName);
        if (!marker.getParentFile().exists()) {
            return;
        }
        touch(marker);
        marked.add(keyOf(dbName, collName, fieldName));
    }

    public void clear(String dbName, String collName, String fieldName) {
        ensureLoaded();
        if (delete(buildingFile(dbName, collName, fieldName), keyOf(dbName, collName, fieldName))) {
            marked.remove(keyOf(dbName, collName, fieldName));
        }
    }

    public boolean isMarked(String dbName, String collName, String fieldName) {
        ensureLoaded();
        final var key = keyOf(dbName, collName, fieldName);
        if (!marked.contains(key)) {
            return false;
        }
        if (buildingFile(dbName, collName, fieldName).exists()) {
            return true;
        }
        marked.remove(key);
        return false;
    }

    public List<String> listMarked() {
        ensureLoaded();
        final var result = new ArrayList<String>();
        for (final var key : marked) {
            final var parts = key.split("\\" + Globals.COLL_IDENTIFIER_SEPARATOR, 3);
            if (isMarked(parts[0], parts[1], parts[2])) {
                result.add(key);
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    public void markDirty(String dbName, String collName) {
        final var marker = dirtyFile(dbName, collName);
        try {
            if (marker.getParentFile().exists()) {
                touch(marker);
            }
        } catch (IOException e) {
            logger.warning(
                    "Could not write the index-dirty marker for " + dbName + "|" + collName + ": " + e.getMessage());
        }
    }

    public void clearDirty(String dbName, String collName) {
        delete(dirtyFile(dbName, collName), dbName + "|" + collName);
    }

    public void clearUncleanStop(String dbName, String collName) {
        delete(uncleanStopFile(dbName, collName), dbName + "|" + collName);
    }

    public List<String> retainUncleanStops() {
        final var retained = new ArrayList<String>();
        for (final var collection : liveCollections()) {
            final var dbName = collection.getParentFile().getName();
            final var collName = collection.getName();
            final var marker = dirtyFile(dbName, collName);
            final var uncleanStop = uncleanStopFile(dbName, collName);
            if (marker.exists()) {
                if (uncleanStop.exists() || marker.renameTo(uncleanStop)) {
                    delete(marker, dbName + "|" + collName);
                } else {
                    logger.warning("Could not retain the unclean-stop marker for " + dbName + "|" + collName
                            + "; the next write to it may retire the warning before REINDEX runs");
                }
            }
            if (marker.exists() || uncleanStop.exists()) {
                retained.add(dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName);
            }
        }
        return retained;
    }

    private void ensureLoaded() {
        final var dbPath = paths.dbPath();
        if (dbPath == null || dbPath.equals(loadedFor)) {
            return;
        }
        loadLock.lock();
        try {
            if (dbPath.equals(loadedFor)) {
                return;
            }
            marked.clear();
            for (final var collection : liveCollections()) {
                loadCollection(collection.getParentFile().getName(), collection);
            }
            loadedFor = dbPath;
        } finally {
            loadLock.unlock();
        }
    }

    private void loadCollection(String dbName, File collection) {
        final var prefix = collection.getName() + "-";
        final var markers = collection
                .listFiles(file -> file.getName().startsWith(prefix) && file.getName().endsWith(BUILDING_SUFFIX));
        if (markers == null) {
            return;
        }
        for (final var marker : markers) {
            final var name = marker.getName();
            final var fieldName = name.substring(prefix.length(), name.length() - BUILDING_SUFFIX.length());
            if (!fieldName.isEmpty()) {
                marked.add(keyOf(dbName, collection.getName(), fieldName));
            }
        }
    }

    private List<File> liveCollections() {
        final var result = new ArrayList<File>();
        final var databases = new File(paths.dbPath()).listFiles(FolderQuarantine::isLiveFolder);
        if (databases == null) {
            return result;
        }
        for (final var database : databases) {
            final var collections = database.listFiles(FolderQuarantine::isLiveFolder);
            if (collections != null) {
                result.addAll(List.of(collections));
            }
        }
        return result;
    }

    private static void touch(File marker) throws IOException {
        if (!marker.exists()) {
            OwnerOnlyPermissions.ensureOwnerOnlyFile(marker.toPath());
            Files.writeString(marker.toPath(), Long.toString(System.currentTimeMillis()));
        }
    }

    private static boolean delete(File marker, String owner) {
        if (marker.exists() && !marker.delete()) {
            logger.warning("Could not clear " + marker.getName() + " for " + owner);
            return false;
        }
        return true;
    }

    private File buildingFile(String dbName, String collName, String fieldName) {
        return new File(paths.collectionFolder(dbName, collName),
                collName + "-" + FilePaths.embedded(fieldName) + BUILDING_SUFFIX);
    }

    private File dirtyFile(String dbName, String collName) {
        return new File(paths.collectionFolder(dbName, collName), collName + DIRTY_SUFFIX);
    }

    private File uncleanStopFile(String dbName, String collName) {
        return new File(paths.collectionFolder(dbName, collName), collName + UNCLEAN_STOP_SUFFIX);
    }

    private static String keyOf(String dbName, String collName, String fieldName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR + fieldName;
    }
}
