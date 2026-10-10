package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;

final class DirtyIndexMarkers {
    private static final String MARKER_SUFFIX = "-indexes.dirty";
    private static final String UNCLEAN_STOP_SUFFIX = "-indexes.unclean";
    private static final Logger logger = Logger.logFor(DirtyIndexMarkers.class);

    private final FilePaths paths;

    DirtyIndexMarkers(FilePaths paths) {
        this.paths = paths;
    }

    void mark(String dbName, String collName) {
        final var marker = markerFile(dbName, collName);
        try {
            if (marker.getParentFile().exists() && !marker.exists()) {
                Files.writeString(marker.toPath(), Long.toString(System.currentTimeMillis()));
            }
        } catch (IOException e) {
            logger.warning(
                    "Could not write the index-dirty marker for " + dbName + "|" + collName + ": " + e.getMessage());
        }
    }

    void clear(String dbName, String collName) {
        delete(markerFile(dbName, collName), dbName, collName);
    }

    void clearUncleanStop(String dbName, String collName) {
        delete(uncleanStopFile(dbName, collName), dbName, collName);
    }

    List<String> listMarked() {
        final var result = new ArrayList<String>();
        for (final var collection : liveCollections()) {
            final var dbName = collection.getParentFile().getName();
            if (markerFile(dbName, collection.getName()).exists()) {
                result.add(dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collection.getName());
            }
        }
        return result;
    }

    List<String> retainUncleanStops() {
        final var retained = new ArrayList<String>();
        for (final var collection : liveCollections()) {
            final var dbName = collection.getParentFile().getName();
            final var collName = collection.getName();
            final var marker = markerFile(dbName, collName);
            final var uncleanStop = uncleanStopFile(dbName, collName);
            if (marker.exists()) {
                if (uncleanStop.exists() || marker.renameTo(uncleanStop)) {
                    delete(marker, dbName, collName);
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

    private static void delete(File marker, String dbName, String collName) {
        if (marker.exists() && !marker.delete()) {
            logger.warning("Could not clear " + marker.getName() + " for " + dbName + "|" + collName);
        }
    }

    private File markerFile(String dbName, String collName) {
        return new File(paths.collectionFolder(dbName, collName), collName + MARKER_SUFFIX);
    }

    private File uncleanStopFile(String dbName, String collName) {
        return new File(paths.collectionFolder(dbName, collName), collName + UNCLEAN_STOP_SUFFIX);
    }
}
