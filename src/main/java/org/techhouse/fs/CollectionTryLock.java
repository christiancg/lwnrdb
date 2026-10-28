package org.techhouse.fs;

public interface CollectionTryLock {
    boolean tryLock(String dbName, String collName);

    void unlock(String dbName, String collName);
}
