package org.techhouse.fs;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class IndexLogOverlay {
    static final IndexLogOverlay ABSENT = new IndexLogOverlay(false, Map.of());

    private final boolean present;
    private final Map<String, List<Change>> byKey;

    record Change(boolean adds, List<String> ids) {
    }

    private IndexLogOverlay(boolean present, Map<String, List<Change>> byKey) {
        this.present = present;
        this.byKey = byKey;
    }

    static IndexLogOverlay of(Map<String, List<Change>> byKey) {
        return new IndexLogOverlay(true, byKey);
    }

    boolean present() {
        return present;
    }

    boolean touches(String fileKey) {
        return byKey.containsKey(fileKey);
    }

    Set<String> keys() {
        return byKey.keySet();
    }

    Set<String> applied(String fileKey, Set<String> base) {
        final var ids = base != null ? base : new HashSet<String>();
        for (final var change : byKey.get(fileKey)) {
            if (change.adds()) {
                ids.addAll(change.ids());
            } else {
                change.ids().forEach(ids::remove);
            }
        }
        return ids;
    }
}
