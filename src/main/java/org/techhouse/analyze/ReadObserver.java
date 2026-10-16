package org.techhouse.analyze;

public interface ReadObserver {
    ReadObserver NONE = new ReadObserver() {
        @Override
        public void scanned(long count) {
        }

        @Override
        public void indexUsed(String field, String lockId) {
        }
    };

    void scanned(long count);

    void indexUsed(String field, String lockId);
}
