package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.CompactionJournal.Kind;
import org.techhouse.fs.CompactionJournal.Marker;
import org.techhouse.log.Logger;

final class CompactionRecovery {
    private static final Logger logger = Logger.logFor(CompactionRecovery.class);

    private final FilePaths paths;
    private final PkIndexStore pkIndexStore;
    private final CompactionJournal journal;

    CompactionRecovery(FilePaths paths, PkIndexStore pkIndexStore, CompactionJournal journal) {
        this.paths = paths;
        this.pkIndexStore = pkIndexStore;
        this.journal = journal;
    }

    record Outcome(List<String> refused, List<PkIndexEntry> completedDeletes) {
    }

    Outcome recoverAll() throws IOException {
        final var refused = new ArrayList<String>();
        final var completedDeletes = new ArrayList<PkIndexEntry>();
        for (final var file : journal.findMarkerFiles()) {
            final var marker = CompactionJournal.decode(Files.readAllBytes(file.toPath()));
            if (marker == null) {
                discardTornMarker(file);
            } else if (recover(marker)) {
                journal.end(marker);
                if (marker.kind() == Kind.DELETE) {
                    completedDeletes.add(marker.preOpEntry());
                }
            } else {
                refused.add(file.getPath());
            }
        }
        return new Outcome(refused, completedDeletes);
    }

    private static void discardTornMarker(File file) throws IOException {
        logger.warning("Discarding the compaction marker " + file.getPath() + ": it was torn while being written,"
                + " which happens before the page is touched");
        Files.deleteIfExists(file.toPath());
    }

    private boolean recover(Marker marker) throws IOException {
        final var label = marker.dbName() + Globals.COLL_IDENTIFIER_SEPARATOR + marker.collName() + " page "
                + marker.page();
        final var page = paths.collectionPage(marker.dbName(), marker.collName(), marker.page());
        if (!page.exists()) {
            logger.warning("Dropping the compaction marker of " + label + ": the page it describes no longer exists");
            return true;
        }
        final var pkRecognised = pkIndexStore.rewriteRows(marker.dbName(), marker.collName(),
                rows -> marker.kind() == Kind.DELETE ? completeDelete(marker, rows) : undoRemoval(marker, rows));
        if (!pkRecognised) {
            logger.error("Leaving the interrupted " + marker.kind() + " of " + marker.id() + " in " + label
                    + " unrecovered: no line of its pk index could be read, and nothing rebuilds the pk index");
            return false;
        }
        if (marker.kind() == Kind.RELOCATE) {
            truncateRelocationTarget(marker);
        }
        restorePage(page, marker);
        logger.warning("Recovered an interrupted " + marker.kind() + " of " + marker.id() + " in " + label + ": "
                + (marker.kind() == Kind.DELETE ? "completed" : "undone to the last acknowledged version"));
        return true;
    }

    private static boolean isPreOp(Marker marker, PkIndexEntry row) {
        return row != null && row.getPage() == marker.page() && row.getPosition() == marker.position()
                && row.getLength() == marker.removedLength() && row.getVersion() == marker.version();
    }

    private static PkIndexEntry rowOf(List<PkIndexEntry> rows, String id) {
        return rows.stream().filter(row -> row.getValue().equals(id)).findFirst().orElse(null);
    }

    private static List<PkIndexEntry> completeDelete(Marker marker, List<PkIndexEntry> rows) {
        final var removed = rowOf(rows, marker.id());
        if (!isPreOp(marker, removed)) {
            return rows;
        }
        rows.remove(removed);
        for (final var row : rows) {
            if (row.getPage() == marker.page() && row.getPosition() > marker.position()) {
                row.setPosition(row.getPosition() - marker.removedLength());
            }
        }
        return rows;
    }

    private static List<PkIndexEntry> undoRemoval(Marker marker, List<PkIndexEntry> rows) {
        final var current = rowOf(rows, marker.id());
        if (isPreOp(marker, current)) {
            return rows;
        }
        if (current != null) {
            rows.remove(current);
        }
        for (final var row : rows) {
            if (row.getPage() == marker.page() && row.getPosition() >= marker.position()) {
                row.setPosition(row.getPosition() + marker.removedLength());
            }
        }
        rows.add(marker.preOpEntry());
        return rows;
    }

    private void truncateRelocationTarget(Marker marker) throws IOException {
        if (marker.targetPage() == CompactionJournal.NO_TARGET) {
            return;
        }
        final var target = paths.collectionPage(marker.dbName(), marker.collName(), marker.targetPage());
        if (target.exists() && target.length() > marker.targetLengthBefore()) {
            try (var writer = new RandomAccessFile(target, Globals.RW_PERMISSIONS)) {
                writer.setLength(marker.targetLengthBefore());
            }
        }
    }

    private static void restorePage(File page, Marker marker) throws IOException {
        final var tail = marker.tail();
        final var keptFrom = marker.kind() == Kind.DELETE ? (int) marker.removedLength() : 0;
        try (var writer = new RandomAccessFile(page, Globals.RW_PERMISSIONS)) {
            writer.seek(marker.position());
            writer.write(tail, keptFrom, tail.length - keptFrom);
            writer.setLength(marker.position() + tail.length - keptFrom);
        }
    }
}
