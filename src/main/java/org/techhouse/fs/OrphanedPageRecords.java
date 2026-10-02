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
            pkIndexStore.bulkIndexNewPKValues(dbName, collName, adoptedIndexEntries);
            logger.warning("Adopted " + adopted.size() + " record(s) of " + dbName + "|" + collName
                    + " that were on a page but missing from its PK index, left by an interrupted write: "
                    + adopted.stream().map(DbEntry::get_id).toList());
        }
        return adopted;
    }

    static long indexedEndOf(List<PkIndexEntry> entries, long page) {
        return entries.stream().filter(entry -> entry.getPage() == page)
                .mapToLong(entry -> entry.getPosition() + entry.getLength()).max().orElse(0L);
    }

    static List<Orphan> orphansOf(File page, String dbName, String collName, long pageNumber, long indexedEnd,
            Set<String> indexedIds) throws IOException {
        final var lock = FileLocks.lockFor(page).writeLock();
        lock.lock();
        try {
            if (!page.exists() || page.length() <= indexedEnd) {
                return List.of();
            }
            final var tail = readFrom(page, indexedEnd);
            final var orphans = new ArrayList<Orphan>();
            final var seen = new HashSet<String>();
            var lineStart = 0;
            for (var i = 0; i < tail.length; i++) {
                if (tail[i] != LINE_FEED) {
                    continue;
                }
                final var lineEnd = i + 1;
                final var text = recordText(tail, lineStart, i);
                if (!text.isEmpty()) {
                    final var orphan = parseOrphan(page, dbName, collName, pageNumber, text);
                    if (orphan == null || indexedIds.contains(orphan.get_id()) || !seen.add(orphan.get_id())) {
                        logger.error("Page " + page.getName() + " holds bytes past its last indexed record that are"
                                + " not whole records of unindexed ids; leaving the page untouched");
                        return List.of();
                    }
                    orphans.add(new Orphan(orphan, indexedEnd + lineStart, lineEnd - lineStart));
                }
                lineStart = lineEnd;
            }
            return orphans;
        } finally {
            lock.unlock();
        }
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
            logger.warning("Unreadable record past the last indexed one in " + page.getName() + ": " + e.getMessage());
            return null;
        }
    }
}
