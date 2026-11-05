package org.techhouse.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Globals;

final class ScanRefusals {
    static final int MAX_REMEMBERED = 1024;
    static final long LIVE_BYTES_CHANGE_DIVISOR = 8L;

    private final Map<String, Refusal> refusals = new ConcurrentHashMap<>();

    private record Refusal(long reachedBytes, long liveBytes) {
        boolean stillRefuses(long freeBudgetBytes, long currentLiveBytes) {
            return freeBudgetBytes < reachedBytes
                    && Math.abs(currentLiveBytes - liveBytes) * LIVE_BYTES_CHANGE_DIVISOR <= liveBytes;
        }
    }

    boolean refusedAt(String collectionIdentifier, long freeBudgetBytes, long liveBytes) {
        final var refusal = refusals.get(collectionIdentifier);
        if (refusal == null) {
            return false;
        }
        if (refusal.stillRefuses(freeBudgetBytes, liveBytes)) {
            return true;
        }
        refusals.remove(collectionIdentifier, refusal);
        return false;
    }

    void refused(String collectionIdentifier, long reachedBytes, long liveBytes) {
        if (refusals.size() >= MAX_REMEMBERED && !refusals.containsKey(collectionIdentifier)) {
            refusals.clear();
        }
        refusals.put(collectionIdentifier, new Refusal(reachedBytes, liveBytes));
    }

    void forget(String collectionIdentifier) {
        refusals.remove(collectionIdentifier);
    }

    void forgetDatabase(String dbName) {
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        refusals.keySet().removeIf(key -> key.startsWith(prefix));
    }

    int size() {
        return refusals.size();
    }
}
