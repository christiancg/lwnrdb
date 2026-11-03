package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.techhouse.data.FieldIndexEntry;

public final class PendingFold {
    public enum Outcome {
        SKIPPED, FOLDED, EMPTIED
    }

    private final String dbName;
    private final String collName;
    private final String fieldName;
    private final String typeLabel;
    private final File indexFile;
    private final File logFile;
    private final long stamp;
    private final long logLength;
    private final List<? extends FieldIndexEntry<?>> survivors;
    private final IndexTypeReader source;

    private PendingFold(String dbName, String collName, String fieldName, String typeLabel, File indexFile,
            List<? extends FieldIndexEntry<?>> survivors, IndexTypeReader source) {
        this.dbName = dbName;
        this.collName = collName;
        this.fieldName = fieldName;
        this.typeLabel = typeLabel;
        this.indexFile = indexFile;
        this.logFile = FilePaths.logFileOf(indexFile);
        this.stamp = FieldIndexLog.stampOf(indexFile);
        this.logLength = logFile.length();
        this.survivors = survivors;
        this.source = source;
    }

    static PendingFold ofSurvivors(String dbName, String collName, String fieldName, String typeLabel, File indexFile,
            List<? extends FieldIndexEntry<?>> survivors) {
        return new PendingFold(dbName, collName, fieldName, typeLabel, indexFile, survivors, null);
    }

    static PendingFold ofSource(String dbName, String collName, String fieldName, String typeLabel, File indexFile,
            IndexTypeReader source) {
        return new PendingFold(dbName, collName, fieldName, typeLabel, indexFile, null, source);
    }

    public String dbName() {
        return dbName;
    }

    public String collName() {
        return collName;
    }

    public String fieldName() {
        return fieldName;
    }

    public long stamp() {
        return stamp;
    }

    File indexFile() {
        return indexFile;
    }

    File logFile() {
        return logFile;
    }

    boolean stillCurrent() {
        return FieldIndexLog.stampOf(indexFile) == stamp && logFile.length() == logLength;
    }

    List<? extends FieldIndexEntry<?>> entries() throws IOException {
        if (survivors != null) {
            return survivors;
        }
        final var folded = source.read();
        if (folded == null) {
            throw new IOException("The " + typeLabel + " index of " + dbName + "|" + collName + "." + fieldName
                    + " could not be read to fold its log, so its changes stay logged;"
                    + " run REINDEX on the collection to rebuild it");
        }
        return folded;
    }
}
