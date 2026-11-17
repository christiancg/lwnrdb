package org.techhouse.fs;

import java.util.List;
import org.techhouse.data.PkIndexEntry;

public interface DeferredApplyListener {
    DeferredApplyListener NONE = new DeferredApplyListener() {
        @Override
        public long replaying(String dbName, String collName, List<PkIndexEntry> rows) {
            return 0L;
        }

        @Override
        public void replayed(String dbName, String collName, List<PkIndexEntry> rows, long generation) {
        }

        @Override
        public void notReplayed(String dbName, String collName, List<PkIndexEntry> rows, long generation) {
        }
    };

    long replaying(String dbName, String collName, List<PkIndexEntry> rows);

    void replayed(String dbName, String collName, List<PkIndexEntry> rows, long generation);

    void notReplayed(String dbName, String collName, List<PkIndexEntry> rows, long generation);
}
