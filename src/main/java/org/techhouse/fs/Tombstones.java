package org.techhouse.fs;

import java.io.IOException;
import java.util.Map;

public final class Tombstones {
    private final FilePaths paths;

    Tombstones(FilePaths paths) {
        this.paths = paths;
    }

    public void append(String dbName, String collName, String id, long version) throws IOException {
        TombstoneStore.append(paths.tombstoneFile(dbName, collName), id, version);
    }

    public void retract(String dbName, String collName, String id, long version) throws IOException {
        TombstoneStore.retract(paths.tombstoneFile(dbName, collName), id, version);
    }

    public void appendAdmin(String key, long version) throws IOException {
        final var file = paths.adminTombstoneFile();
        MetadataFileStore.ensureFolder(file.getParentFile(), "cluster", "admin");
        TombstoneStore.append(file, key, version);
    }

    public Map<String, Long> readAdmin() throws IOException {
        return TombstoneStore.read(paths.adminTombstoneFile());
    }

    public Map<String, Long> read(String dbName, String collName) throws IOException {
        return TombstoneStore.read(paths.tombstoneFile(dbName, collName));
    }

    public void compact(String dbName, String collName, long minVersionToKeep) throws IOException {
        TombstoneStore.compact(paths.tombstoneFile(dbName, collName), minVersionToKeep);
    }
}
