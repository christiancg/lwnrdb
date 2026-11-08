package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ex.PkRebuildRefusedException;
import org.techhouse.log.Logger;

final class PkIndexRebuild {
    private static final Logger logger = Logger.logFor(PkIndexRebuild.class);
    private static final long UNVERSIONED = 0L;

    private PkIndexRebuild() {
    }

    private record IdLine(String id, long position, int length) {
    }

    static PkRebuild plan(FilePaths paths, PkIndexStore store, String dbName, String collName, Collection<Long> pages)
            throws IOException {
        final var previous = store.readRecognisedPkIndex(dbName, collName);
        final var known = previous.map(PkIndexRebuild::sortedById).orElseGet(List::of);
        final var matched = new BitSet(known.size());
        final var rebuilt = new ArrayList<PkIndexEntry>(known.size());
        final var fresh = new ArrayList<PkIndexEntry>();
        for (final var page : new TreeSet<>(pages)) {
            for (final var line : idLinesOf(paths.collectionPage(dbName, collName, page), dbName, collName)) {
                final var at = Collections.binarySearch(known, line.id());
                final PkIndexEntry row;
                if (at < 0) {
                    row = new PkIndexEntry(dbName, collName, line.id(), line.position(), line.length(), page,
                            UNVERSIONED);
                    fresh.add(row);
                } else {
                    final var previousRow = known.get(at);
                    if (matched.get(at)) {
                        throw onTwoPages(dbName, collName, line.id(), previousRow.getPage(), page);
                    }
                    matched.set(at);
                    row = new PkIndexEntry(dbName, collName, previousRow.getValue(), line.position(), line.length(),
                            page, previousRow.getVersion());
                    known.set(at, row);
                }
                rebuilt.add(row);
            }
        }
        refuseRepeatedIds(dbName, collName, fresh);
        final var vanished = new ArrayList<PkIndexEntry>();
        for (var at = matched.nextClearBit(0); at < known.size(); at = matched.nextClearBit(at + 1)) {
            vanished.add(known.get(at));
        }
        return new PkRebuild(rebuilt, fresh.size(), previous.isPresent(), previous.isPresent() ? fresh : List.of(),
                vanished);
    }

    private static List<PkIndexEntry> sortedById(List<PkIndexEntry> rows) {
        rows.sort(Comparator.comparing(PkIndexEntry::getValue));
        return rows;
    }

    private static void refuseRepeatedIds(String dbName, String collName, List<PkIndexEntry> fresh)
            throws PkRebuildRefusedException {
        fresh.sort(Comparator.comparing(PkIndexEntry::getValue));
        for (var at = 1; at < fresh.size(); at++) {
            final var before = fresh.get(at - 1);
            final var row = fresh.get(at);
            if (before.getValue().equals(row.getValue())) {
                throw onTwoPages(dbName, collName, row.getValue(), before.getPage(), row.getPage());
            }
        }
    }

    private static PkRebuildRefusedException onTwoPages(String dbName, String collName, String id, long first,
            long second) {
        return refused(dbName, collName, "_id '" + id + "' is on pages " + first + " and " + second);
    }

    static void apply(PkIndexStore store, String dbName, String collName, PkRebuild rebuild) throws IOException {
        store.replaceAll(dbName, collName, rebuild.rows());
        logRebuild(dbName, collName, rebuild);
    }

    private static List<IdLine> idLinesOf(File page, String dbName, String collName) throws IOException {
        final var lock = FileLocks.lockRead(page);
        try {
            if (!page.exists() || page.length() == 0L) {
                return List.of();
            }
            final var bytes = PageLines.readFrom(page, 0L);
            final var lines = new ArrayList<IdLine>();
            final var seen = new HashSet<String>();
            var start = 0;
            while (start < bytes.length) {
                final var end = FileLocks.lineEnd(bytes, start);
                if (end >= bytes.length) {
                    throw refused(dbName, collName, page.getName() + " does not end on a whole record");
                }
                final var next = FileLocks.nextLineStart(bytes, end);
                if (!PageHoles.isSkippable(bytes, start, end)) {
                    lines.add(idLine(page, dbName, collName, bytes, start, end, next, seen));
                }
                start = next;
            }
            return lines;
        } finally {
            lock.unlock();
        }
    }

    private static IdLine idLine(File page, String dbName, String collName, byte[] bytes, int start, int end, int next,
            Set<String> seen) throws PkRebuildRefusedException {
        final var id = PageLines.idOf(bytes, start, end);
        if (id == null) {
            throw refused(dbName, collName, page.getName() + ": a record at offset " + start + " has no readable _id");
        }
        if (!seen.add(id)) {
            throw refused(dbName, collName, page.getName() + ": _id '" + id + "' appears twice");
        }
        return new IdLine(id, start, next - start);
    }

    static void refuseStaleWriteIntents(WriteIntentRecovery recovery, String dbName, String collName)
            throws IOException {
        final var stale = recovery.replayableIntentsOf(dbName, collName);
        if (!stale.isEmpty()) {
            throw refused(dbName, collName,
                    "write intent(s) " + stale + " were left by writes this process could not"
                            + " clean up after, and a rebuild would freeze whatever half of each write the pages hold."
                            + " Restart the server, which recovers them");
        }
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
        logger.warning(summary + "; adopted " + rebuild.appeared().stream().map(PkIndexEntry::getValue).toList()
                + " at version 0, retired " + rebuild.vanished().stream().map(PkIndexEntry::getValue).toList());
    }
}
