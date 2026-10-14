package org.techhouse.cache;

import java.util.HashMap;
import java.util.Map;
import org.techhouse.data.FieldIndexEntry;

public final class FieldIndexLookup {
    private final Map<String, Located> byId = new HashMap<>();
    private volatile int ids;

    public record Located(String typeIdentifier, FieldIndexEntry<Object> entry, Located next) {
    }

    public Located locate(String id) {
        return byId.get(id);
    }

    public void add(String id, String typeIdentifier, FieldIndexEntry<Object> entry) {
        final var current = byId.get(id);
        for (var at = current; at != null; at = at.next()) {
            if (at.entry() == entry) {
                return;
            }
        }
        byId.put(id, new Located(typeIdentifier, entry, current));
        if (current == null) {
            publishSize();
        }
    }

    public void moved(String id, Located now) {
        if (now == null) {
            if (byId.remove(id) != null) {
                publishSize();
            }
        } else if (byId.put(id, now) == null) {
            publishSize();
        }
    }

    public int size() {
        return ids;
    }

    private void publishSize() {
        ids = byId.size();
    }
}
