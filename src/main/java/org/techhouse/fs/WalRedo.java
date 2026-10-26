package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.log.Logger;

final class WalRedo {
    private static final Logger logger = Logger.logFor(WalRedo.class);
    private static final int LOG_SEARCH_DEPTH = 4;
    private static final long NO_PAGE = -1L;

    private WalRedo() {
    }

    static List<PkIndexEntry> redoAll(FilePaths paths) throws IOException {
        final var completedDeletes = new ArrayList<PkIndexEntry>();
        for (final var log : findLogs(paths)) {
            completedDeletes.addAll(redo(paths, log));
        }
        return completedDeletes;
    }

    private static List<File> findLogs(FilePaths paths) throws IOException {
        final var root = new File(paths.dbPath()).toPath();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root, LOG_SEARCH_DEPTH)) {
            return walk.map(Path::toFile)
                    .filter(file -> file.isFile() && file.getName().endsWith(Globals.WAL_EXTENSION))
                    .filter(file -> WriteIntentJournal.isUnderLiveFolders(root.toFile(), file)).sorted().toList();
        }
    }

    private static List<PkIndexEntry> redo(FilePaths paths, File log) throws IOException {
        final var decoded = WalCodec.decodeAll(Files.readAllBytes(log.toPath()));
        final var checkpoint = decoded.checkpoint();
        if (checkpoint == null) {
            logger.warning("The write-ahead log " + log.getPath() + " holds no checkpoint, so it was never written"
                    + " past its first record; nothing in it is replayed");
            return List.of();
        }
        final var dbName = checkpoint.dbName();
        final var collName = checkpoint.collName();
        final var records = decoded.records();
        replay(paths, dbName, collName, decoded);
        if (SyncedWrites.enabled()) {
            cutPastLoggedEnds(paths, checkpoint, decoded);
        }
        new WriteAheadLog(paths).track(dbName, collName);
        if (!records.isEmpty()) {
            logger.warning("Replayed " + records.size() + " logged write(s) of " + dbName
                    + Globals.COLL_IDENTIFIER_SEPARATOR + collName + " from its write-ahead log");
        }
        return withPendingIds(dbName, collName, writtenIds(dbName, collName, records), decoded.pendingIds());
    }

    private static List<PkIndexEntry> withPendingIds(String dbName, String collName, List<PkIndexEntry> written,
            List<String> pendingIds) {
        if (pendingIds.isEmpty()) {
            return written;
        }
        final var rows = new LinkedHashMap<String, PkIndexEntry>();
        for (final var id : pendingIds) {
            rows.put(id, new PkIndexEntry(dbName, collName, id, 0L, 0L, 0L, 0L));
        }
        written.forEach(row -> rows.put(row.getValue(), row));
        return List.copyOf(rows.values());
    }

    static void replay(FilePaths paths, String dbName, String collName, WalCodec.Decoded decoded) throws IOException {
        for (final var record : decoded.records()) {
            for (final var change : record.changes()) {
                if (skipped(decoded, record, change)) {
                    continue;
                }
                if (change.target() == WalTarget.PK_WRITE) {
                    write(paths.pkIndexFile(dbName, collName), change);
                } else if (change.target() == WalTarget.PAGE_BLANK) {
                    blank(paths.collectionPage(dbName, collName, change.page()), change);
                } else {
                    write(paths.collectionPage(dbName, collName, change.page()), change);
                }
            }
        }
        PageTails.forgetUnder(paths.collectionFolder(dbName, collName));
        PkIndexStore.forgetState(paths.pkIndexFile(dbName, collName));
    }

    private static boolean skipped(WalCodec.Decoded decoded, WalRecord record, WalChange change) {
        final var rewrite = change.target() == WalTarget.PK_WRITE ? null : decoded.rewrites().get(change.page());
        return rewrite != null && rewrite.skips(record, change);
    }

    private static void write(File file, WalChange change) throws IOException {
        OwnerOnly.ensureOwnerOnlyFile(file.toPath());
        WalPlan.writeAt(file, change.offset(), change.bytes(), 0, change.bytes().length);
    }

    private static void blank(File page, WalChange change) throws IOException {
        if (!page.exists() || page.length() < change.end()) {
            throw new IOException("The write-ahead log blanks " + change.offset() + "+" + change.length() + " of "
                    + page.getName() + ", past its " + page.length() + "-byte end; the page is not the one the log"
                    + " was written against");
        }
        try (var channel = FileChannel.open(page.toPath(), StandardOpenOption.WRITE)) {
            PageHoles.blank(channel, change.offset(), change.length());
        }
    }

    private static void cutPastLoggedEnds(FilePaths paths, WalCheckpoint checkpoint, WalCodec.Decoded decoded)
            throws IOException {
        final var dbName = checkpoint.dbName();
        final var collName = checkpoint.collName();
        final var groupBase = decoded.groupBase();
        final var loggedEnds = new HashMap<Long, Long>();
        for (final var record : decoded.records()) {
            for (final var change : record.changes()) {
                if (!skipped(decoded, record, change)) {
                    loggedEnds.merge(change.target() == WalTarget.PK_WRITE ? NO_PAGE : change.page(), change.end(),
                            Math::max);
                }
            }
        }
        for (final var page : DocumentPageStore.pageFileLengths(paths, dbName, collName).entrySet()) {
            final var rewrite = decoded.rewrites().get(page.getKey());
            final var floor = rewrite != null
                    ? rewrite.floor()
                    : checkpoint.pageLengths().getOrDefault(page.getKey(), 0L);
            final var keep = Math.max(floor, loggedEnds.getOrDefault(page.getKey(), 0L));
            cutTo(paths.collectionPage(dbName, collName, page.getKey()), page.getValue(), keep);
        }
        final var pkIndex = paths.pkIndexFile(dbName, collName);
        final var pkEnd = loggedEnds.get(NO_PAGE);
        if (pkIndex.exists() && (pkEnd != null || groupBase != WalCodec.NO_GROUP_BASE)) {
            final var logged = Math.max(groupBase, pkEnd == null ? 0L : pkEnd);
            cutTo(pkIndex, pkIndex.length(), Math.max(checkpoint.pkIndexLength(), logged));
        }
    }

    private static void cutTo(File file, long length, long keep) throws IOException {
        if (length <= keep) {
            return;
        }
        if (!FileLocks.truncateTo(file, keep,
                "Could not cut " + file.getPath() + " back to the end the write-ahead" + " log knows")) {
            throw new IOException("Could not cut the unlogged end of " + file.getPath());
        }
        logger.warning("Cut " + (length - keep) + " unlogged byte(s) from the end of " + file.getName()
                + ": they were written by a write whose log record never became durable");
    }

    private static List<PkIndexEntry> writtenIds(String dbName, String collName, List<WalRecord> records) {
        final var written = new LinkedHashMap<String, PkIndexEntry>();
        for (final var record : records) {
            final var blankedPage = blankedPageOf(record);
            for (final var change : record.changes()) {
                if (change.target() == WalTarget.PK_WRITE) {
                    collectRows(dbName, collName, change.bytes(), blankedPage, written);
                }
            }
        }
        return List.copyOf(written.values());
    }

    private static long blankedPageOf(WalRecord record) {
        for (final var change : record.changes()) {
            if (change.target() == WalTarget.PAGE_BLANK) {
                return change.page();
            }
        }
        return 0L;
    }

    private static void collectRows(String dbName, String collName, byte[] bytes, long blankedPage,
            Map<String, PkIndexEntry> written) {
        var start = 0;
        while (start < bytes.length) {
            final var end = FileLocks.lineEnd(bytes, start);
            if (end > start) {
                final var deletedId = PkIndexEntry.deletedIdOf(bytes, start, end);
                if (deletedId == null) {
                    final var row = PkIndexEntry.fromIndexFileBytes(dbName, collName, bytes, start, end);
                    written.remove(row.getValue());
                    written.put(row.getValue(), row);
                } else {
                    written.remove(deletedId);
                    written.put(deletedId, new PkIndexEntry(dbName, collName, deletedId, 0L, 0L, blankedPage, 0L));
                }
            }
            start = FileLocks.nextLineStart(bytes, end);
        }
    }
}
