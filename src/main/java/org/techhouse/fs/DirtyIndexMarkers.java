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
        final var marker = markerFile(dbName, collName);
        if (marker.exists() && !marker.delete()) {
            logger.warning("Could not clear the index-dirty marker for " + dbName + "|" + collName);
        }
    }

    List<String> listMarked() {
        final var result = new ArrayList<String>();
        final var databases = new File(paths.dbPath()).listFiles(File::isDirectory);
        if (databases == null) {
            return result;
        }
        for (final var database : databases) {
            final var collections = database.listFiles(File::isDirectory);
            if (collections == null) {
                continue;
            }
            for (final var collection : collections) {
                if (markerFile(database.getName(), collection.getName()).exists()) {
                    result.add(database.getName() + Globals.COLL_IDENTIFIER_SEPARATOR + collection.getName());
                }
            }
        }
        return result;
    }

    private File markerFile(String dbName, String collName) {
        return new File(paths.collectionFolder(dbName, collName), collName + MARKER_SUFFIX);
    }
}
