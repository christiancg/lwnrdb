package org.techhouse.fs;

import java.util.Map;

record WalCheckpoint(long seq, String dbName, String collName, long pkIndexLength, Map<Long, Long> pageLengths) {
}
