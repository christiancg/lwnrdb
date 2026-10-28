package org.techhouse.cache;

import java.io.IOException;
import java.util.List;
import org.techhouse.data.PkIndexEntry;

@FunctionalInterface
public interface PkRows {
    List<PkIndexEntry> rows(String dbName, String collName) throws IOException;
}
