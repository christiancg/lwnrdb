package org.techhouse.cache;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;

final class CacheFitWarnings {
    private static final Logger logger = Logger.logFor(CacheFitWarnings.class);

    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    void pkIndexOutgrows(String dbName, String collName, long bytes, long maxBytes) {
        if (bytes > maxBytes && warned.add(Cache.getCollectionIdentifier(dbName, collName))) {
            logger.warning("The primary-key index of " + dbName + "|" + collName + " needs about " + bytes
                    + " bytes of cache, more than maxMemory (" + maxBytes + "), so it is never cached: keyed reads"
                    + " scan pk.idx and writes re-read it. Raise maxMemory above " + bytes + " to keep it resident.");
        }
    }

    void fieldIndexOutgrows(String dbName, String collName, String indexKey, long bytes, long maxBytes) {
        final var key = Cache.getCollectionIdentifier(dbName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR + indexKey;
        if (bytes > maxBytes && warned.add(key)) {
            logger.warning("The field index " + indexKey + " of " + dbName + "|" + collName + " needs about " + bytes
                    + " bytes of cache, more than maxMemory (" + maxBytes + "), so it is never cached and every"
                    + " read or write of it loads it from disk. Raise maxMemory above " + bytes
                    + " to keep it resident.");
        }
    }

    void forgetCollection(String collectionIdentifier) {
        final var prefix = collectionIdentifier + Globals.COLL_IDENTIFIER_SEPARATOR;
        warned.removeIf(key -> key.equals(collectionIdentifier) || key.startsWith(prefix));
    }

    void forgetByPrefix(String prefix) {
        warned.removeIf(key -> key.startsWith(prefix));
    }
}
