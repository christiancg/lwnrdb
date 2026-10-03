package org.techhouse.fs;

import java.io.File;
import org.techhouse.config.Globals;

public final class FolderQuarantine {
    private final FilePaths paths;

    FolderQuarantine(FilePaths paths) {
        this.paths = paths;
    }

    static boolean isLiveFolder(File file) {
        return file.isDirectory() && !file.getName().contains(Globals.QUARANTINE_INFIX);
    }

    public boolean moveCollectionAside(String dbName, String collectionName, long incarnation) {
        final var collectionFolder = paths.collectionFolder(dbName, collectionName);
        if (!collectionFolder.exists()) {
            return false;
        }
        return moveAside(collectionFolder, collectionName + Globals.QUARANTINE_INFIX + incarnation + "-");
    }

    public boolean moveDatabaseAside(String dbName) {
        final var dbFolder = paths.rawDatabaseFolder(dbName);
        if (!dbFolder.exists()) {
            return false;
        }
        return moveAside(dbFolder, dbName + Globals.QUARANTINE_INFIX);
    }

    public boolean holdsLeftoverCollection(String dbName, String collectionName) {
        final var files = paths.collectionFolder(dbName, collectionName).listFiles();
        if (files == null) {
            return false;
        }
        for (final var file : files) {
            if (file.isDirectory() || file.length() > 0) {
                return true;
            }
        }
        return false;
    }

    public boolean holdsLeftoverDatabase(String dbName) {
        final var files = paths.rawDatabaseFolder(dbName).listFiles();
        return files != null && files.length > 0;
    }

    private static boolean moveAside(File folder, String targetPrefix) {
        final var stamp = targetPrefix + System.currentTimeMillis();
        var target = new File(folder.getParentFile(), stamp);
        for (var attempt = 1; target.exists(); attempt++) {
            target = new File(folder.getParentFile(), stamp + "-" + attempt);
        }
        return folder.renameTo(target);
    }
}
