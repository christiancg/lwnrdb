package org.techhouse.fs;

import java.io.IOException;
import java.util.List;
import org.techhouse.config.Globals;

public final class MetadataFiles {
    private final FilePaths paths;

    MetadataFiles(FilePaths paths) {
        this.paths = paths;
    }

    public void writeCollectionSchema(String dbName, String collName, String schemaJson) throws IOException {
        MetadataFileStore.write(paths.schemaFile(dbName, collName), schemaJson);
    }

    public String readCollectionSchema(String dbName, String collName) throws IOException {
        return MetadataFileStore.read(paths.schemaFile(dbName, collName));
    }

    public boolean deleteCollectionSchema(String dbName, String collName) throws IOException {
        return MetadataFileStore.delete(paths.schemaFile(dbName, collName));
    }

    public void writeProcedure(String dbName, String name, String json) throws IOException {
        MetadataFileStore.ensureFolder(paths.proceduresFolder(dbName), "procedures", dbName);
        MetadataFileStore.write(paths.procedureFile(dbName, name), json);
    }

    public String readProcedure(String dbName, String name) throws IOException {
        final var file = paths.procedureFile(dbName, name);
        return MetadataFileStore.isListedExactly(file) ? MetadataFileStore.read(file) : null;
    }

    public boolean deleteProcedure(String dbName, String name) throws IOException {
        final var file = paths.procedureFile(dbName, name);
        return MetadataFileStore.isListedExactly(file) && MetadataFileStore.delete(file);
    }

    public List<String> listProcedureNames(String dbName) throws IOException {
        return MetadataFileStore.listNames(paths.proceduresFolder(dbName), Globals.PROCEDURE_FILE_EXTENSION);
    }

    public void writeSchedule(String dbName, String name, String json) throws IOException {
        MetadataFileStore.ensureFolder(paths.schedulesFolder(dbName), "schedules", dbName);
        MetadataFileStore.write(paths.scheduleFile(dbName, name), json);
    }

    public String readSchedule(String dbName, String name) throws IOException {
        final var file = paths.scheduleFile(dbName, name);
        return MetadataFileStore.isListedExactly(file) ? MetadataFileStore.read(file) : null;
    }

    public boolean deleteSchedule(String dbName, String name) throws IOException {
        final var file = paths.scheduleFile(dbName, name);
        return MetadataFileStore.isListedExactly(file) && MetadataFileStore.delete(file);
    }

    public List<String> listScheduleNames(String dbName) throws IOException {
        return MetadataFileStore.listNames(paths.schedulesFolder(dbName), Globals.SCHEDULE_FILE_EXTENSION);
    }

    public void writeTriggers(String dbName, String collName, String json) throws IOException {
        MetadataFileStore.write(paths.triggersFile(dbName, collName), json);
    }

    public String readTriggers(String dbName, String collName) throws IOException {
        return MetadataFileStore.read(paths.triggersFile(dbName, collName));
    }

    public boolean deleteTriggers(String dbName, String collName) throws IOException {
        return MetadataFileStore.delete(paths.triggersFile(dbName, collName));
    }
}
