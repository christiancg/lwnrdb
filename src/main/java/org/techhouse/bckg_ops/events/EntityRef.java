package org.techhouse.bckg_ops.events;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.data.DbEntry;

public record EntityRef(String id, long page, long byteSize, long previousByteSize) {
    public static EntityRef of(DbEntry entry) {
        final long byteSize = entry.byteSize();
        return new EntityRef(entry.get_id(), entry.getPage(), byteSize, entry.getPreviousByteSize());
    }

    public static List<EntityRef> allOf(List<DbEntry> entries) {
        final var refs = new ArrayList<EntityRef>(entries.size());
        for (final var entry : entries) {
            refs.add(of(entry));
        }
        return refs;
    }
}
