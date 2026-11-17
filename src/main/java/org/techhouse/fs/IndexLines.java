package org.techhouse.fs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.techhouse.data.FieldIndexEntry;

final class IndexLines {
    private IndexLines() {
    }

    static List<String> ids(byte[] bytes, int separator, int to) {
        final var ids = new ArrayList<String>();
        addIds(ids, bytes, separator, to);
        return ids;
    }

    static void addIds(Collection<String> ids, byte[] bytes, int separator, int to) {
        var start = separator + 1;
        for (var at = start; at <= to; at++) {
            if (at == to || bytes[at] == IndexFileCursor.SEPARATOR) {
                if (at > start) {
                    ids.add(FieldIndexEntry
                            .unescapeIndexToken(new String(bytes, start, at - start, StandardCharsets.UTF_8)));
                }
                start = at + 1;
            }
        }
    }

    static long idCount(byte[] bytes, int separator, int to) {
        var count = 0L;
        var start = separator + 1;
        for (var at = start; at <= to; at++) {
            if (at == to || bytes[at] == IndexFileCursor.SEPARATOR) {
                if (at > start) {
                    count++;
                }
                start = at + 1;
            }
        }
        return count;
    }
}
