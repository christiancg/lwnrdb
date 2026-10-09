package org.techhouse.fs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.log.Logger;

final class DanglingPkEntries {
    private static final Logger logger = Logger.logFor(DanglingPkEntries.class);

    private DanglingPkEntries() {
    }

    static List<PkIndexEntry> retireAll(PkIndexStore pkIndexStore, String dbName, String collName,
            Map<Long, Long> pageLengths) throws IOException {
        final var recognised = pkIndexStore.readRecognisedPkIndex(dbName, collName);
        if (recognised.isEmpty()) {
            return List.of();
        }
        final var dangling = danglingIn(recognised.get(), pageLengths);
        if (dangling.isEmpty()) {
            return List.of();
        }
        final var rewritten = pkIndexStore.rewriteRows(dbName, collName, rows -> rows.stream()
                .filter(row -> !isDangling(row, pageLengths)).collect(Collectors.toCollection(ArrayList::new)));
        if (!rewritten) {
            return List.of();
        }
        logger.error("Retired " + dangling.size() + " PK index entr" + (dangling.size() == 1 ? "y" : "ies") + " of "
                + dbName + "|" + collName + " whose record reaches past the end of its page; the record was lost in"
                + " an unclean stop: " + dangling.stream().map(PkIndexEntry::getValue).toList());
        return dangling;
    }

    private static List<PkIndexEntry> danglingIn(List<PkIndexEntry> entries, Map<Long, Long> pageLengths) {
        return entries.stream().filter(entry -> isDangling(entry, pageLengths)).toList();
    }

    private static boolean isDangling(PkIndexEntry entry, Map<Long, Long> pageLengths) {
        return PageRegions.reachesPastEnd(entry, pageLengths.getOrDefault(entry.getPage(), 0L));
    }
}
