package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.log.Logger;

final class PkIndexStore {
    private static final Logger logger = Logger.logFor(PkIndexStore.class);
    private static final long DELETED = -1L;
    private static final Map<String, PkFileState> fileStates = new ConcurrentHashMap<>();

    private final FilePaths paths;
    private final WriteAheadLog wal;

    record PkFileState(long length, FileTime modified, long liveRows, long deadRows) {
        boolean needsCompaction() {
            return deadRows >= Math.max(liveRows, Globals.PK_LOG_MIN_DEAD_ROWS);
        }
    }

    record Folded(List<PkIndexEntry> live, int rows, boolean malformed, boolean torn) {
        boolean unrecognised() {
            return malformed && rows == 0;
        }

        boolean needsRewrite() {
            return malformed || torn || rows - live.size() >= Math.max(live.size(), Globals.PK_LOG_MIN_DEAD_ROWS);
        }
    }

    PkIndexStore(FilePaths paths) {
        this.paths = paths;
        this.wal = new WriteAheadLog(paths);
    }

    record PkAppend(File file, PkFileState before, long offset) {
    }

    void appendRows(String dbName, String collName, List<PkIndexEntry> rows) throws IOException {
        if (rows.isEmpty()) {
            return;
        }
        final var plan = new WalPlan(paths, this, dbName, collName);
        rows.forEach(plan::insertRow);
        plan.commit();
    }

    void appendReplacements(String dbName, String collName, List<PkIndexEntry> rows) throws IOException {
        if (rows.isEmpty()) {
            return;
        }
        final var plan = new WalPlan(paths, this, dbName, collName);
        rows.forEach(plan::replaceRow);
        plan.commit();
    }

    PkAppend prepareAppend(String dbName, String collName) throws IOException {
        final var indexFile = paths.pkIndexFile(dbName, collName);
        final var before = trustedOrHealed(dbName, collName, indexFile);
        if (before == null || before.modified() == null) {
            OwnerOnly.ensureOwnerOnlyFile(indexFile.toPath());
            return new PkAppend(indexFile, before, indexFile.length());
        }
        return new PkAppend(indexFile, before, before.length());
    }

    void appended(String dbName, String collName, PkAppend append, int rowCount, long liveDelta) {
        final var before = append.before();
        if (before == null || before.length() != append.offset()) {
            forget(append.file());
            return;
        }
        final var after = remember(append.file(), before.liveRows() + liveDelta,
                before.deadRows() + rowCount - liveDelta);
        if (after != null && after.needsCompaction()) {
            compact(dbName, collName, append.file());
        }
    }

    private PkFileState trustedOrHealed(String dbName, String collName, File indexFile) throws IOException {
        final var known = trusted(indexFile);
        if (known != null) {
            return known;
        }
        final var folded = fold(dbName, collName, indexFile);
        if (folded.unrecognised()) {
            throw new IOException("No line in " + indexFile.getName() + " could be read as a PK index entry, so it is"
                    + " not the file this write expects; refusing to append to it. Rebuild it with REINDEX and"
                    + " \"primaryKey\": true, or repair or restore it");
        }
        if (folded.needsRewrite()) {
            writeRows(dbName, collName, indexFile, folded.live());
        }
        return trusted(indexFile);
    }

    private void compact(String dbName, String collName, File indexFile) {
        try {
            final var folded = fold(dbName, collName, indexFile);
            if (folded.unrecognised()) {
                forget(indexFile);
                return;
            }
            writeRows(dbName, collName, indexFile, folded.live());
        } catch (IOException e) {
            forget(indexFile);
            logger.warning("Appended to " + indexFile.getName() + " but could not compact it; the rows it holds are"
                    + " all valid, and the next append rewrites it first and is refused while that keeps failing: "
                    + e.getMessage());
        }
    }

    boolean rewriteRows(String dbName, String collectionName, UnaryOperator<List<PkIndexEntry>> change)
            throws IOException {
        final var indexFile = paths.pkIndexFile(dbName, collectionName);
        final var lock = FileLocks.lockWrite(indexFile);
        try {
            final var folded = indexFile.exists()
                    ? fold(dbName, collectionName, indexFile)
                    : new Folded(new ArrayList<>(), 0, false, false);
            if (folded.unrecognised()) {
                return false;
            }
            final var changed = change.apply(new ArrayList<>(folded.live()));
            changed.sort(Comparator.comparing(PkIndexEntry::getValue));
            writeRows(dbName, collectionName, indexFile, changed);
            return true;
        } finally {
            lock.unlock();
        }
    }

    void replaceAll(String dbName, String collectionName, List<PkIndexEntry> entries) throws IOException {
        final var indexFile = paths.pkIndexFile(dbName, collectionName);
        final var lock = FileLocks.lockWrite(indexFile);
        try {
            final var sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparing(PkIndexEntry::getValue));
            writeRows(dbName, collectionName, indexFile, sorted);
            wal.checkpoint(dbName, collectionName);
        } finally {
            lock.unlock();
        }
    }

    private void writeRows(String dbName, String collName, File indexFile, List<PkIndexEntry> rows) throws IOException {
        wal.checkpoint(dbName, collName);
        forget(indexFile);
        FileLocks.rewriteFileAtomically(indexFile.toPath(), out -> {
            final var lines = new PkLineWriter(out);
            for (final var row : rows) {
                lines.write(row);
            }
        });
        remember(indexFile, rows.size(), 0L);
    }

    List<PkIndexEntry> readWholePkIndexFile(String dbName, String collectionName) throws IOException {
        final var folded = readHealed(dbName, collectionName);
        if (folded.unrecognised()) {
            throw new IOException("No line in " + paths.pkIndexFile(dbName, collectionName).getName()
                    + " could be read as a PK index entry; it is left untouched. Rebuild it with REINDEX and"
                    + " \"primaryKey\": true, or repair or restore it, before reading or writing " + dbName + "|"
                    + collectionName);
        }
        return folded.live();
    }

    Optional<List<PkIndexEntry>> readRecognisedPkIndex(String dbName, String collectionName) throws IOException {
        final var folded = readHealed(dbName, collectionName);
        return folded.unrecognised() ? Optional.empty() : Optional.of(folded.live());
    }

    private Folded readHealed(String dbName, String collectionName) throws IOException {
        final var indexFile = paths.pkIndexFile(dbName, collectionName);
        if (!indexFile.exists()) {
            return new Folded(new ArrayList<>(), 0, false, false);
        }
        final Folded folded;
        final var readLock = FileLocks.lockRead(indexFile);
        try {
            folded = fold(dbName, collectionName, indexFile);
        } finally {
            readLock.unlock();
        }
        if (folded.unrecognised()) {
            logger.error("No line in " + indexFile.getName() + " could be read as a PK index entry, so it is not"
                    + " the file this loader expects; leaving it untouched, because it must never be rewritten from a"
                    + " read that understood none of it. REINDEX with \"primaryKey\": true rebuilds it from the pages.");
            return folded;
        }
        if (!folded.needsRewrite()) {
            return folded;
        }
        final var writeLock = FileLocks.lockWrite(indexFile);
        try {
            final var refolded = fold(dbName, collectionName, indexFile);
            if (!refolded.unrecognised() && refolded.needsRewrite()) {
                writeRows(dbName, collectionName, indexFile, refolded.live());
            }
            return refolded;
        } finally {
            writeLock.unlock();
        }
    }

    private static Folded fold(String dbName, String collectionName, File indexFile) throws IOException {
        final var bytes = Files.readAllBytes(indexFile.toPath());
        final var rows = new ArrayList<PkIndexEntry>();
        var malformed = false;
        var torn = false;
        var start = 0;
        while (start < bytes.length) {
            final var end = FileLocks.lineEnd(bytes, start);
            if (end >= bytes.length) {
                torn = true;
                logger.warning("Dropping the unterminated last line of " + indexFile.getName()
                        + ": it was torn by an interrupted append");
                break;
            }
            if (end > start) {
                final var row = parseRow(dbName, collectionName, bytes, start, end, indexFile);
                if (row == null) {
                    malformed = true;
                } else {
                    rows.add(row);
                }
            }
            start = FileLocks.nextLineStart(bytes, end);
        }
        final var rowCount = rows.size();
        rows.sort(Comparator.comparing(PkIndexEntry::getValue));
        keepLastLiveRowOfEachId(rows);
        if (malformed || torn) {
            forget(indexFile);
        } else {
            remember(indexFile, rows.size(), rowCount - rows.size());
        }
        return new Folded(rows, rowCount, malformed, torn);
    }

    private static PkIndexEntry parseRow(String dbName, String collectionName, byte[] bytes, int start, int end,
            File indexFile) {
        try {
            final var deletedId = PkIndexEntry.deletedIdOf(bytes, start, end);
            if (deletedId != null) {
                return new PkIndexEntry(dbName, collectionName, deletedId, 0L, DELETED, 0L, 0L);
            }
            return PkIndexEntry.fromIndexFileBytes(dbName, collectionName, bytes, start, end);
        } catch (Exception e) {
            logger.warning("Removing malformed PK index entry in " + indexFile.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private static void keepLastLiveRowOfEachId(List<PkIndexEntry> sortedInFileOrder) {
        var kept = 0;
        for (final var row : sortedInFileOrder) {
            if (kept > 0 && sortedInFileOrder.get(kept - 1).getValue().equals(row.getValue())) {
                sortedInFileOrder.set(kept - 1, row);
            } else {
                sortedInFileOrder.set(kept++, row);
            }
        }
        var live = 0;
        for (var index = 0; index < kept; index++) {
            final var row = sortedInFileOrder.get(index);
            if (row.getLength() != DELETED) {
                sortedInFileOrder.set(live++, row);
            }
        }
        sortedInFileOrder.subList(live, sortedInFileOrder.size()).clear();
    }

    private static PkFileState trusted(File indexFile) {
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(indexFile.toPath(), BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            return new PkFileState(0L, null, 0L, 0L);
        } catch (IOException e) {
            return null;
        }
        final var known = fileStates.get(indexFile.getAbsolutePath());
        if (known == null || known.modified() == null) {
            return null;
        }
        return attributes.size() == known.length() && attributes.lastModifiedTime().equals(known.modified())
                ? known
                : null;
    }

    private static PkFileState remember(File indexFile, long liveRows, long deadRows) {
        try {
            final var attributes = Files.readAttributes(indexFile.toPath(), BasicFileAttributes.class);
            final var state = new PkFileState(attributes.size(), attributes.lastModifiedTime(), liveRows, deadRows);
            fileStates.put(indexFile.getAbsolutePath(), state);
            return state;
        } catch (IOException e) {
            forget(indexFile);
            return null;
        }
    }

    private static void forget(File indexFile) {
        fileStates.remove(indexFile.getAbsolutePath());
    }

    static void forgetState(File indexFile) {
        forget(indexFile);
    }
}
