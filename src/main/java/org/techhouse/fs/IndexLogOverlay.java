package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.techhouse.data.FieldIndexEntry;

final class IndexLogOverlay {
    private static final byte ADD = '+';
    private static final byte REMOVE = '-';
    private static final IndexLogOverlay ABSENT = new IndexLogOverlay(false);

    private final boolean present;
    private final Map<String, List<Change>> byKey = new LinkedHashMap<>();

    private record Change(boolean adds, List<String> ids) {
    }

    private IndexLogOverlay(boolean present) {
        this.present = present;
    }

    static IndexLogOverlay read(File log, IndexKeyOrder order, Predicate<Object> selects) throws IOException {
        try (var cursor = IndexFileCursor.open(log)) {
            if (cursor == null) {
                return ABSENT;
            }
            final var overlay = new IndexLogOverlay(true);
            cursor.seek(0);
            while (cursor.nextLine()) {
                if (!cursor.lineTerminated()) {
                    throw new IndexFileDeclined();
                }
                overlay.add(cursor, order, selects);
            }
            return overlay;
        }
    }

    private void add(IndexFileCursor cursor, IndexKeyOrder order, Predicate<Object> selects) {
        final var bytes = cursor.lineBytes();
        final var from = cursor.lineFrom();
        final var to = cursor.lineTo();
        if (to - from < 2 || bytes[from] != ADD && bytes[from] != REMOVE
                || bytes[from + 1] != IndexFileCursor.SEPARATOR) {
            throw new IndexFileDeclined();
        }
        final var keyFrom = from + 2;
        final var keyTo = cursor.separatorIn(keyFrom, to);
        final var value = order.parse(bytes, keyFrom, keyTo);
        if (selects.test(value)) {
            byKey.computeIfAbsent(FieldIndexEntry.fileKeyOf(value), _ -> new ArrayList<>())
                    .add(new Change(bytes[from] == ADD, IndexLines.ids(bytes, keyTo, to)));
        }
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
