package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.log.Logger;

final class OrphanedPageRecords {
    private static final Logger logger = Logger.logFor(OrphanedPageRecords.class);
    private static final byte LINE_FEED = '\n';
    private static final byte CARRIAGE_RETURN = '\r';
    private static final long UNVERSIONED = 0L;

    private OrphanedPageRecords() {
    }

    record Orphan(DbEntry entry, long position, int length) {
    }

    static List<DbEntry> adoptAll(FilePaths paths, PkIndexStore pkIndexStore, String dbName, String collName,
            Iterable<Long> pages) throws IOException {
        final var recognised = pkIndexStore.readRecognisedPkIndex(dbName, collName);
        if (recognised.isEmpty()) {
            logger.error("The PK index of " + dbName + "|" + collName + " could not be read, so its pages were not"
                    + " checked for records it does not name");
            return List.of();
        }
        final var entries = recognised.get();
        final var indexedIds = entries.stream().map(PkIndexEntry::getValue).collect(Collectors.toSet());
        final var orphansByPage = new HashMap<Long, List<Orphan>>();
        for (final var page : pages) {
            final var orphans = orphansOf(paths.collectionPage(dbName, collName, page), dbName, collName, page,
                    indexedEndOf(entries, page), indexedIds);
            if (!orphans.isEmpty()) {
                orphansByPage.put(page, orphans);
            }
        }
        dropPagesSharingAnId(orphansByPage);
        final var adopted = new ArrayList<DbEntry>();
        final var adoptedIndexEntries = new ArrayList<PkIndexEntry>();
        for (final var pageOrphans : orphansByPage.entrySet()) {
            for (final var orphan : pageOrphans.getValue()) {
                adopted.add(orphan.entry());
                adoptedIndexEntries.add(new PkIndexEntry(dbName, collName, orphan.entry().get_id(), orphan.position(),
                        orphan.length(), pageOrphans.getKey(), UNVERSIONED));
            }
        }
        if (!adoptedIndexEntries.isEmpty()) {
            pkIndexStore.appendRows(dbName, collName, adoptedIndexEntries);
            logger.warning("Adopted " + adopted.size() + " record(s) of " + dbName + "|" + collName
                    + " that were on a page but missing from its PK index, left by an interrupted write: "
                    + adopted.stream().map(DbEntry::get_id).toList());
        }
        return adopted;
    }

    static void cutUncommittedCopies(FilePaths paths, PkIndexStore pkIndexStore, String dbName, String collName,
            Iterable<Long> pages) throws IOException {
        final var recognised = pkIndexStore.readRecognisedPkIndex(dbName, collName);
        if (recognised.isEmpty() || !SyncedWrites.enabled()) {
            return;
        }
        final var entries = recognised.get();
        final var indexedIds = entries.stream().map(PkIndexEntry::getValue).collect(Collectors.toSet());
        for (final var page : pages) {
            final var file = paths.collectionPage(dbName, collName, page);
            final var scan = scanRecords(file, dbName, collName, page, indexedEndOf(entries, page));
            final var copies = scan.records();
            if (scan.readable() && !copies.isEmpty()
                    && copies.stream().allMatch(copy -> indexedIds.contains(copy.entry().get_id()))) {
                cutUncommittedCopies(file, copies);
            }
        }
    }

    static List<PkIndexEntry> retireDangling(PkIndexStore pkIndexStore, String dbName, String collName,
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
        return entry.getPosition() + entry.getLength() > pageLengths.getOrDefault(entry.getPage(), 0L);
    }

    static long indexedEndOf(List<PkIndexEntry> entries, long page) {
        return entries.stream().filter(entry -> entry.getPage() == page)
                .mapToLong(entry -> entry.getPosition() + entry.getLength()).max().orElse(0L);
    }

    record PageRecordScan(List<Orphan> records, String unreadable, boolean unterminatedTail) {
        private static final PageRecordScan EMPTY = new PageRecordScan(List.of(), null, false);

        boolean readable() {
            return unreadable == null;
        }
    }

    static List<Orphan> orphansOf(File page, String dbName, String collName, long pageNumber, long indexedEnd,
            Set<String> indexedIds) throws IOException {
        final var scan = scanRecords(page, dbName, collName, pageNumber, indexedEnd);
        final var held = scan.records().stream().filter(orphan -> indexedIds.contains(orphan.entry().get_id()))
                .toList();
        if (scan.readable() && held.isEmpty()) {
            return scan.records();
        }
        if (scan.readable() && held.size() == scan.records().size() && SyncedWrites.enabled()) {
            cutUncommittedCopies(page, held);
            return List.of();
        }
        logger.error("Page " + page.getName() + " holds bytes past its last indexed record that are"
                + " not whole records of unindexed ids; leaving the page untouched");
        return List.of();
    }

    private static void cutUncommittedCopies(File page, List<Orphan> copies) {
        final boolean cut;
        final var lock = FileLocks.lockWrite(page);
        try {
            cut = FileLocks.truncateTo(page, copies.getFirst().position(),
                    "Could not cut the uncommitted copies past" + " the last indexed record of " + page.getName()
                            + "; a scan may answer them until the next" + " startup");
        } finally {
            lock.unlock();
        }
        if (!cut) {
            return;
        }
        logger.warning("Cut " + copies.size() + " uncommitted cop" + (copies.size() == 1 ? "y" : "ies") + " from "
                + page.getName() + ": records of ids the PK index already holds elsewhere, appended by a write whose"
                + " intent a power loss lost, so the indexed versions are kept: "
                + copies.stream().map(copy -> copy.entry().get_id()).toList());
    }

    static PageRecordScan scanRecords(File page, String dbName, String collName, long pageNumber, long fromOffset)
            throws IOException {
        final var lock = FileLocks.lockWrite(page);
        try {
            if (!page.exists() || page.length() <= fromOffset) {
                return PageRecordScan.EMPTY;
            }
            final var tail = readFrom(page, fromOffset);
            final var records = new ArrayList<Orphan>();
            final var seen = new HashSet<String>();
            var lineStart = 0;
            for (var i = 0; i < tail.length; i++) {
                if (tail[i] != LINE_FEED) {
                    continue;
                }
                final var lineEnd = i + 1;
                final var text = PageHoles.isHole(tail, lineStart) ? "" : recordText(tail, lineStart, i);
                if (!text.isEmpty()) {
                    final var record = parseOrphan(page, dbName, collName, pageNumber, text);
                    if (record == null) {
                        return unreadable("a record at offset " + (fromOffset + lineStart) + " has no readable _id");
                    }
                    if (!seen.add(record.get_id())) {
                        return unreadable("_id '" + record.get_id() + "' appears twice");
                    }
                    records.add(new Orphan(record, fromOffset + lineStart, lineEnd - lineStart));
                }
                lineStart = lineEnd;
            }
            return new PageRecordScan(records, null, lineStart < tail.length);
        } finally {
            lock.unlock();
        }
    }

    private static PageRecordScan unreadable(String reason) {
        return new PageRecordScan(List.of(), reason, false);
    }

    private static void dropPagesSharingAnId(Map<Long, List<Orphan>> orphansByPage) {
        final var pagesById = new HashMap<String, Set<Long>>();
        for (final var pageOrphans : orphansByPage.entrySet()) {
            for (final var orphan : pageOrphans.getValue()) {
                pagesById.computeIfAbsent(orphan.entry().get_id(), _ -> new HashSet<>()).add(pageOrphans.getKey());
            }
        }
        for (final var idPages : pagesById.entrySet()) {
            if (idPages.getValue().size() > 1) {
                logger.error("Record " + idPages.getKey() + " is unindexed on pages " + idPages.getValue()
                        + "; leaving those pages untouched");
                idPages.getValue().forEach(orphansByPage::remove);
            }
        }
    }

    private static byte[] readFrom(File page, long offset) throws IOException {
        try (var reader = new RandomAccessFile(page, Globals.R_PERMISSIONS)) {
            final var bytes = new byte[Math.toIntExact(reader.length() - offset)];
            reader.seek(offset);
            reader.readFully(bytes);
            return bytes;
        }
    }

    private static String recordText(byte[] tail, int start, int lineFeed) {
        final var end = lineFeed > start && tail[lineFeed - 1] == CARRIAGE_RETURN ? lineFeed - 1 : lineFeed;
        return new String(Arrays.copyOfRange(tail, start, end), StandardCharsets.UTF_8);
    }

    private static DbEntry parseOrphan(File page, String dbName, String collName, long pageNumber, String text) {
        try {
            final var entry = DbEntry.fromString(dbName, collName, text);
            if (entry.get_id() == null) {
                return null;
            }
            entry.setPage(pageNumber);
            entry.setVersion(UNVERSIONED);
            return entry;
        } catch (RuntimeException e) {
            logger.warning("Unreadable record in " + page.getName() + ": " + e.getMessage());
            return null;
        }
    }
}
