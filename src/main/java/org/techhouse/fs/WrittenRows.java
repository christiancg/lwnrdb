package org.techhouse.fs;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.data.PkIndexEntry;

record WrittenRows(Map<String, PkIndexEntry> replaced, String deletedId) {
    static final WrittenRows NONE = new WrittenRows(Map.of(), null);

    static WrittenRows deleted(String id) {
        return id == null ? NONE : new WrittenRows(Map.of(), id);
    }

    static WrittenRows replaced(Collection<PkIndexEntry> rows) {
        final var byId = new HashMap<String, PkIndexEntry>(rows.size() * 2);
        for (final var row : rows) {
            byId.put(row.getValue(), row);
        }
        return new WrittenRows(byId, null);
    }

    PkIndexEntry rowOf(List<PkIndexEntry> residentRows, String id) {
        if (id.equals(deletedId)) {
            return null;
        }
        final var row = replaced.get(id);
        return row != null ? row : PageRows.rowOf(residentRows, id);
    }

    List<PkIndexEntry> rowsOn(PkIndexStore store, List<PkIndexEntry> residentRows, String dbName, String collName,
            long page) throws IOException {
        final var rows = PageRows.of(store, residentRows, dbName, collName, page, deletedId);
        if (replaced.isEmpty()) {
            return rows;
        }
        rows.removeIf(row -> replaced.containsKey(row.getValue()));
        for (final var row : replaced.values()) {
            if (row.getPage() == page) {
                rows.add(row);
            }
        }
        return rows;
    }

    void reposition(VacuumedPage vacuumed) {
        if (!replaced.isEmpty()) {
            vacuumed.reposition(replaced.values());
        }
    }
}
