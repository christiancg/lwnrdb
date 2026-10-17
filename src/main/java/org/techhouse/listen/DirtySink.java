package org.techhouse.listen;

@FunctionalInterface
public interface DirtySink {
    void markDirty(String dbName, String collName);
}
