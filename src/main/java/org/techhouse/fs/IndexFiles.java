package org.techhouse.fs;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.techhouse.data.IndexKind;

public final class IndexFiles {
    private final FilePaths paths;

    IndexFiles(FilePaths paths) {
        this.paths = paths;
    }

    public Set<String> equalsIds(String dbName, String collName, String fieldName, Class<?> type, List<?> values)
            throws IOException {
        final var index = paths.indexFile(dbName, collName, fieldName, IndexKind.fileLabel(type));
        return index == null ? null : IndexFileLookup.anyOfIds(index, type, values);
    }

    public Set<String> rangeIds(String dbName, String collName, String fieldName, Class<?> type,
            List<IndexFileLookup.Bound> bounds) throws IOException {
        final var index = paths.indexFile(dbName, collName, fieldName, IndexKind.fileLabel(type));
        return index == null ? null : IndexFileLookup.rangeIds(index, type, bounds);
    }

    public Set<String> complementIds(String dbName, String collName, String fieldName, Class<?> type, List<?> values)
            throws IOException {
        final var index = paths.indexFile(dbName, collName, fieldName, IndexKind.fileLabel(type));
        return index == null ? null : IndexFileLookup.complementIds(index, type, values);
    }

    public long equalsCount(String dbName, String collName, String fieldName, Class<?> type, List<?> values)
            throws IOException {
        final var index = paths.indexFile(dbName, collName, fieldName, IndexKind.fileLabel(type));
        return index == null ? IndexFileLookup.DECLINED : IndexFileLookup.anyOfCount(index, type, values);
    }

    public long rangeCount(String dbName, String collName, String fieldName, Class<?> type,
            List<IndexFileLookup.Bound> bounds) throws IOException {
        final var index = paths.indexFile(dbName, collName, fieldName, IndexKind.fileLabel(type));
        return index == null ? IndexFileLookup.DECLINED : IndexFileLookup.rangeCount(index, type, bounds);
    }

    public long idCount(String dbName, String collName, String fieldName, Class<?> type) throws IOException {
        final var index = paths.indexFile(dbName, collName, fieldName, IndexKind.fileLabel(type));
        return index == null ? IndexFileLookup.DECLINED : IndexFileLookup.idCount(index, type);
    }
}
