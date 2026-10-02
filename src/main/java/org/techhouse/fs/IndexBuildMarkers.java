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

public final class IndexBuildMarkers {
    private static final String MARKER_SUFFIX = ".building";
    private static final Logger logger = Logger.logFor(IndexBuildMarkers.class);

    private final FilePaths paths;
    private final Set<String> marked = ConcurrentHashMap.newKeySet();
    private final ReentrantLock loadLock = new ReentrantLock();
    private volatile String loadedFor;

    IndexBuildMarkers(FilePaths paths) {
        this.paths = paths;
    }

    public void mark(String dbName, String collName, String fieldName) throws IOException {
        ensureLoaded();
        final var marker = markerFile(dbName, collName, fieldName);
        if (!marker.getParentFile().exists()) {
            return;
        }
        if (!marker.exists()) {
            Files.writeString(marker.toPath(), Long.toString(System.currentTimeMillis()));
        }
        marked.add(keyOf(dbName, collName, fieldName));
    }

    public void clear(String dbName, String collName, String fieldName) {
        ensureLoaded();
        final var marker = markerFile(dbName, collName, fieldName);
        if (marker.exists() && !marker.delete()) {
            logger.warning("Could not clear the index-build marker for " + keyOf(dbName, collName, fieldName));
            return;
        }
        marked.remove(keyOf(dbName, collName, fieldName));
    }

    public boolean isMarked(String dbName, String collName, String fieldName) {
        ensureLoaded();
        final var key = keyOf(dbName, collName, fieldName);
        if (!marked.contains(key)) {
            return false;
        }
        if (markerFile(dbName, collName, fieldName).exists()) {
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
            loadFrom(new File(dbPath));
            loadedFor = dbPath;
        } finally {
            loadLock.unlock();
        }
    }

    private void loadFrom(File root) {
        final var databases = root.listFiles(File::isDirectory);
        if (databases == null) {
            return;
        }
        for (final var database : databases) {
            final var collections = database.listFiles(File::isDirectory);
            if (collections == null) {
                continue;
            }
            for (final var collection : collections) {
                loadCollection(database.getName(), collection);
            }
        }
    }

    private void loadCollection(String dbName, File collection) {
        final var prefix = collection.getName() + "-";
        final var markers = collection
                .listFiles(file -> file.getName().startsWith(prefix) && file.getName().endsWith(MARKER_SUFFIX));
        if (markers == null) {
            return;
        }
        for (final var marker : markers) {
            final var name = marker.getName();
            final var fieldName = name.substring(prefix.length(), name.length() - MARKER_SUFFIX.length());
            if (!fieldName.isEmpty()) {
                marked.add(keyOf(dbName, collection.getName(), fieldName));
            }
        }
    }

    private File markerFile(String dbName, String collName, String fieldName) {
        return new File(paths.collectionFolder(dbName, collName), collName + "-" + fieldName + MARKER_SUFFIX);
    }

    private static String keyOf(String dbName, String collName, String fieldName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR + fieldName;
    }
}
