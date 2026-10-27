package org.techhouse.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;

final class SharedIdLists {
    private SharedIdLists() {
    }

    static void share(List<PkIndexEntry> pk, List<FieldIndexEntry<?>> copy) {
        if (pk != null && copy instanceof FieldIndexList list) {
            list.shareWithPk();
        }
    }

    static String canonicalId(List<PkIndexEntry> pk, String id) {
        if (pk == null) {
            return null;
        }
        final var row = PkIndexList.rowOf(pk, id);
        return row == null ? null : row.getValue();
    }

    static long sharedBytesOf(List<FieldIndexEntry<?>> entries) {
        return entries instanceof FieldIndexList list ? list.sharedIdBytes() : 0L;
    }

    static List<String> sharersOf(Map<String, List<FieldIndexEntry<?>>> indexes) {
        final var sharers = new ArrayList<String>();
        if (indexes != null) {
            indexes.forEach((indexKey, entries) -> {
                if (entries instanceof FieldIndexList list && list.sharesPkIds()) {
                    sharers.add(indexKey);
                }
            });
        }
        return sharers;
    }
}
