package org.techhouse.fs;

import java.io.IOException;

public interface CheckpointGuard {
    CheckpointGuard NO_INDEX_WORK = new CheckpointGuard() {
        @Override
        public boolean holdsIndexWork(String dbName, String collName) {
            return false;
        }

        @Override
        public void persistIndexWork(String dbName, String collName) {
        }
    };

    boolean holdsIndexWork(String dbName, String collName);

    void persistIndexWork(String dbName, String collName) throws IOException;
}
