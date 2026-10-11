package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ex.PkRebuildRefusedException;
import org.techhouse.log.Logger;

final class PkIndexRebuild {
    private static final Logger logger = Logger.logFor(PkIndexRebuild.class);
    private static final long UNVERSIONED = 0L;

    private PkIndexRebuild() {
    }

    static PkRebuild plan(FilePaths paths, PkIndexStore store, String dbName, String collName, Collection<Long> pages)
            throws IOException {
        final var previous = store.readRecognisedPkIndex(dbName, collName);
        final Map<String, PkIndexEntry> previousById = previous
                .map(entries -> entries.stream().collect(Collectors.toMap(PkIndexEntry::getValue, Function.identity())))
                .orElseGet(Map::of);
        final var pageById = new HashMap<String, Long>();
        final var rebuilt = new ArrayList<PkIndexEntry>();
        final var appeared = new ArrayList<DbEntry>();
        var unversioned = 0;
        for (final var page : new TreeSet<>(pages)) {
            for (final var record : wholeRecordsOf(paths.collectionPage(dbName, collName, page), dbName, collName,
                    page)) {
                final var id = record.entry().get_id();
                final var otherPage = pageById.putIfAbsent(id, page);
                if (otherPage != null) {
                    throw refused(dbName, collName, "_id '" + id + "' is on pages " + otherPage + " and " + page);
                }
                final var known = previousById.get(id);
                if (known == null) {
                    unversioned++;
                    if (previous.isPresent()) {
                        appeared.add(record.entry());
                    }
                }
                rebuilt.add(new PkIndexEntry(dbName, collName, id, record.position(), record.length(), page,
                        known == null ? UNVERSIONED : known.getVersion()));
            }
        }
        final var vanished = previousById.values().stream().filter(entry -> !pageById.containsKey(entry.getValue()))
                .toList();
        return new PkRebuild(rebuilt, unversioned, previous.isPresent(), appeared, vanished);
    }

    static void apply(PkIndexStore store, String dbName, String collName, PkRebuild rebuild) throws IOException {
        store.replaceAll(dbName, collName, rebuild.rows());
        logRebuild(dbName, collName, rebuild);
    }

    private static List<OrphanedPageRecords.Orphan> wholeRecordsOf(File page, String dbName, String collName,
            long pageNumber) throws IOException {
        final var scan = OrphanedPageRecords.scanRecords(page, dbName, collName, pageNumber, 0L);
        if (!scan.readable()) {
            throw refused(dbName, collName, page.getName() + ": " + scan.unreadable());
        }
        if (scan.unterminatedTail()) {
            throw refused(dbName, collName, page.getName() + " does not end on a whole record");
        }
        return scan.records();
    }

    static PkRebuildRefusedException refused(String dbName, String collName, String reason) {
        return new PkRebuildRefusedException(
                "The PK index of " + dbName + "|" + collName + " was not rebuilt and is left untouched: " + reason);
    }

    private static void logRebuild(String dbName, String collName, PkRebuild rebuild) {
        final var summary = "Rebuilt the PK index of " + dbName + "|" + collName + " from its page files: "
                + rebuild.entries() + " entr" + (rebuild.entries() == 1 ? "y" : "ies");
        if (!rebuild.previousReadable()) {
            logger.warning(summary + "; the previous PK index could not be read, so every entry is at version 0 and"
                    + " any replica's copy or tombstone outranks it");
            return;
        }
        logger.warning(summary + "; adopted " + rebuild.appeared().stream().map(DbEntry::get_id).toList()
                + " at version 0, retired " + rebuild.vanished().stream().map(PkIndexEntry::getValue).toList());
    }
}
