package org.techhouse.fs;

import java.io.File;
import org.techhouse.config.Globals;
import org.techhouse.ex.InvalidPathSegmentException;

// Every builder funnels through databaseFolder, making this the only place the reserved logical db
// name ADMIN_PAGES_DB_NAME is translated to the physical admin/pages subfolder.
final class FilePaths {
    private String dbPath;

    void useDbPath(String dbPath) {
        this.dbPath = dbPath;
    }

    String dbPath() {
        return dbPath;
    }

    File databaseFolder(String dbName) {
        return new File(dbPath + Globals.FILE_SEPARATOR + resolveDbPathSegment(segment(dbName)));
    }

    File rawDatabaseFolder(String dbName) {
        return new File(dbPath + Globals.FILE_SEPARATOR + segment(dbName));
    }

    File collectionFolder(String dbName, String collectionName) {
        return new File(databaseFolder(dbName).getPath() + Globals.FILE_SEPARATOR + segment(collectionName));
    }

    File collectionPage(String dbName, String collectionName, long page) {
        return new File(collectionPrefix(dbName, collectionName) + Globals.FILE_PAGE_SEPARATOR + page
                + Globals.DB_FILE_EXTENSION);
    }

    File intentLog(String dbName, String collectionName) {
        return new File(collectionPrefix(dbName, collectionName) + Globals.INTENT_LOG_EXTENSION);
    }

    File vacuumFile(String dbName, String collectionName, long page) {
        return new File(collectionPrefix(dbName, collectionName) + Globals.FILE_PAGE_SEPARATOR + page
                + Globals.VACUUM_FILE_EXTENSION);
    }

    File indexFile(String dbName, String collectionName, String fieldName, String indexType) {
        if (isReservedFileName(fieldName)) {
            return null;
        }
        return new File(collectionPrefix(dbName, collectionName) + Globals.INDEX_FILE_NAME_SEPARATOR
                + embedded(fieldName) + Globals.INDEX_FILE_NAME_SEPARATOR + indexType + Globals.INDEX_FILE_EXTENSION);
    }

    static File logFileOf(File indexFile) {
        return new File(indexFile.getPath() + Globals.INDEX_LOG_SUFFIX);
    }

    private static boolean isReservedFileName(String fieldName) {
        return Globals.PK_FIELD.equals(fieldName) || Globals.TOMBSTONE_FILE_NAME.equals(fieldName);
    }

    File pkIndexFile(String dbName, String collectionName) {
        return new File(collectionPrefix(dbName, collectionName) + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.PK_INDEX_FILE_NAME + Globals.INDEX_FILE_EXTENSION);
    }

    File adminTombstoneFile() {
        return new File(dbPath + Globals.FILE_SEPARATOR + Globals.CLUSTER_FOLDER + Globals.FILE_SEPARATOR
                + Globals.CLUSTER_ADMIN_TOMBSTONES_FILE);
    }

    File nodeIdFile() {
        return new File(dbPath + Globals.FILE_SEPARATOR + Globals.CLUSTER_FOLDER + Globals.FILE_SEPARATOR
                + Globals.CLUSTER_NODE_ID_FILE);
    }

    File tombstoneFile(String dbName, String collectionName) {
        return new File(collectionPrefix(dbName, collectionName) + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.TOMBSTONE_FILE_NAME + Globals.INDEX_FILE_EXTENSION);
    }

    File schemaFile(String dbName, String collectionName) {
        return new File(collectionPrefix(dbName, collectionName) + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.SCHEMA_FILE_NAME + Globals.SCHEMA_FILE_EXTENSION);
    }

    File triggersFile(String dbName, String collectionName) {
        return new File(collectionPrefix(dbName, collectionName) + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.TRIGGERS_FILE_NAME + Globals.TRIGGERS_FILE_EXTENSION);
    }

    File adminPagesFolder() {
        return new File(dbPath + Globals.FILE_SEPARATOR + Globals.ADMIN_DB_NAME + Globals.FILE_SEPARATOR
                + Globals.ADMIN_PAGES_FOLDER);
    }

    File proceduresFolder(String dbName) {
        return new File(databaseFolder(dbName).getPath() + Globals.FILE_SEPARATOR + Globals.PROCEDURES_FOLDER);
    }

    // The name becomes a path segment here and nowhere else; RequestValidator has already matched it
    // against the collection-name rule, so a separator or a '..' segment is unrepresentable.
    File procedureFile(String dbName, String name) {
        return new File(proceduresFolder(dbName).getPath() + Globals.FILE_SEPARATOR + segment(name)
                + Globals.PROCEDURE_FILE_EXTENSION);
    }

    File schedulesFolder(String dbName) {
        return new File(databaseFolder(dbName).getPath() + Globals.FILE_SEPARATOR + Globals.SCHEDULES_FOLDER);
    }

    File scheduleFile(String dbName, String name) {
        return new File(schedulesFolder(dbName).getPath() + Globals.FILE_SEPARATOR + segment(name)
                + Globals.SCHEDULE_FILE_EXTENSION);
    }

    private String collectionPrefix(String dbName, String collectionName) {
        return collectionFolder(dbName, collectionName).getPath() + Globals.FILE_SEPARATOR + collectionName;
    }

    private static String segment(String name) {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name) || hasSeparator(name)) {
            throw new InvalidPathSegmentException();
        }
        return name;
    }

    static String embedded(String name) {
        if (name == null || name.isEmpty() || hasSeparator(name)) {
            throw new InvalidPathSegmentException();
        }
        return name;
    }

    private static boolean hasSeparator(String name) {
        for (var i = 0; i < name.length(); i++) {
            final var c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '\0') {
                return true;
            }
        }
        return false;
    }

    private String resolveDbPathSegment(String dbName) {
        if (Globals.ADMIN_PAGES_DB_NAME.equals(dbName)) {
            return Globals.ADMIN_DB_NAME + Globals.FILE_SEPARATOR + Globals.ADMIN_PAGES_FOLDER;
        }
        return dbName;
    }
}
