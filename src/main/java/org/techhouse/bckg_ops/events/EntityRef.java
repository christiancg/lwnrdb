package org.techhouse.bckg_ops.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.techhouse.data.DbEntry;
import org.techhouse.ops.index.IndexedValue;

public record EntityRef(String id, long page, long byteSize, long previousByteSize,
        Map<String, IndexedValue> priorIndexed) {
    public EntityRef(String id, long page, long byteSize, long previousByteSize) {
        this(id, page, byteSize, previousByteSize, null);
    }

    public static EntityRef of(DbEntry entry) {
        return of(entry, null);
    }

    public static EntityRef of(DbEntry entry, Map<String, IndexedValue> priorIndexed) {
        final long byteSize = entry.byteSize();
        return new EntityRef(entry.get_id(), entry.getPage(), byteSize, entry.getPreviousByteSize(), priorIndexed);
    }

    public static List<EntityRef> allOf(List<DbEntry> entries) {
        return allOf(entries, null);
    }

    public static List<EntityRef> allOf(List<DbEntry> entries, Map<String, Map<String, IndexedValue>> priorsById) {
        final var refs = new ArrayList<EntityRef>(entries.size());
        for (final var entry : entries) {
            refs.add(of(entry, priorsById == null ? null : priorsById.get(entry.get_id())));
        }
        return refs;
    }
}
