package org.techhouse.concurrency;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class CollectionFences {
    private static final Set<String> fenced = ConcurrentHashMap.newKeySet();

    private CollectionFences() {
    }

    static void add(String lockName) {
        fenced.add(lockName);
    }

    static void remove(String lockName) {
        fenced.remove(lockName);
    }

    static boolean contains(String lockName) {
        return !fenced.isEmpty() && fenced.contains(lockName);
    }

    static boolean any(Collection<String> lockNames) {
        if (fenced.isEmpty()) {
            return false;
        }
        for (final var lockName : lockNames) {
            if (fenced.contains(lockName)) {
                return true;
            }
        }
        return false;
    }
}
