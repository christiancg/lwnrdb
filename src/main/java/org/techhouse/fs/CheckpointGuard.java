package org.techhouse.fs;

import java.util.Collection;
import java.util.List;

public interface CheckpointGuard {
    CheckpointGuard NO_INDEX_WORK = new CheckpointGuard() {
        @Override
        public boolean holdsIndexWork(String dbName, String collName) {
            return false;
        }

        @Override
        public Collection<String> pendingIds(String dbName, String collName) {
            return List.of();
        }
    };

    boolean holdsIndexWork(String dbName, String collName);

    Collection<String> pendingIds(String dbName, String collName);
}
