package org.techhouse.fs;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

final class PageVacuum {
    private static final Logger logger = Logger.logFor(PageVacuum.class);
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private final Configuration configuration = Configuration.getInstance();

    private final FilePaths paths;
    private final PkIndexStore pkIndexStore;
    private final WriteIntentJournal journal;
    private final WalRewriteBarrier barrier;
    private volatile Consumer<VacuumedPage> listener = _ -> {
    };

    PageVacuum(FilePaths paths, PkIndexStore pkIndexStore, WriteIntentJournal journal) {
        this.paths = paths;
        this.pkIndexStore = pkIndexStore;
        this.journal = journal;
        this.barrier = new WalRewriteBarrier(new WriteAheadLog(paths));
    }

    void onVacuum(Consumer<VacuumedPage> vacuumListener) {
        this.listener = vacuumListener;
    }

    VacuumedPage vacuumIfNeeded(String dbName, String collName, long pageNumber, long appendedBytes,
            PagePlacement placement, List<PkIndexEntry> residentRows) throws IOException {
        return vacuumIfNeeded(dbName, collName, pageNumber, appendedBytes, placement, residentRows, null);
    }

    VacuumedPage vacuumIfNeeded(String dbName, String collName, long pageNumber, long appendedBytes,
            PagePlacement placement, List<PkIndexEntry> residentRows, LongFunction<PageOccupancy> occupancy)
            throws IOException {
        if (placement == PagePlacement.APPEND) {
            return null;
        }
        final var page = paths.collectionPage(dbName, collName, pageNumber);
        final var fileLength = page.length();
        final var maxPageSize = configuration.getMaxPageSize();
        if (placement == null && fileLength + appendedBytes <= maxPageSize) {
            return null;
        }
        final var known = residentRows == null || occupancy == null ? null : occupancy.apply(pageNumber);
        if (known != null) {
            if (placement == null && PagePlacement.forStay(known.liveBytes(), fileLength, appendedBytes,
                    maxPageSize) != PagePlacement.VACUUM_THEN_APPEND) {
                return null;
            }
            return vacuum(dbName, collName, pageNumber, residentRows, known, null);
        }
        final var pageRows = PageRows.of(pkIndexStore, residentRows, dbName, collName, pageNumber);
        if (placement == null && PagePlacement.forStay(PageRows.liveBytes(pageRows), fileLength, appendedBytes,
                maxPageSize) != PagePlacement.VACUUM_THEN_APPEND) {
            return null;
        }
        return vacuum(dbName, collName, pageNumber, pageRows);
    }

    VacuumedPage vacuum(String dbName, String collName, long pageNumber, List<PkIndexEntry> pageRows)
            throws IOException {
        final var page = paths.collectionPage(dbName, collName, pageNumber);
        return rewrite(dbName, collName, pageNumber, lines -> {
            requireEveryLineNamed(page, lines, pageRows);
            return pageRows;
        });
    }

    VacuumedPage vacuum(String dbName, String collName, long pageNumber, List<PkIndexEntry> residentRows,
            PageOccupancy occupancy, String excludedId) throws IOException {
        final var page = paths.collectionPage(dbName, collName, pageNumber);
        return rewrite(dbName, collName, pageNumber, lines -> {
            final var found = lines.isEmpty() ? null : rowsOfLines(lines, residentRows, pageNumber, occupancy);
            if (found != null) {
                return found;
            }
            final var pageRows = PageRows.of(pkIndexStore, residentRows, dbName, collName, pageNumber, excludedId);
            requireEveryLineNamed(page, lines, pageRows);
            return pageRows;
        });
    }

    VacuumedPage vacuumAfterDelete(String dbName, String collName, long pageNumber, List<PkIndexEntry> residentRows,
            PageOccupancy after, String deletedId) throws IOException {
        return vacuum(dbName, collName, pageNumber, residentRows, after, deletedId);
    }

    static List<PkIndexEntry> rowsOfLines(List<PageLines.Line> lines, List<PkIndexEntry> residentRows, long pageNumber,
            PageOccupancy occupancy) {
        if (occupancy == null || lines.size() != occupancy.rows()) {
            return null;
        }
        final var rows = new ArrayList<PkIndexEntry>(lines.size());
        for (final var line : lines) {
            final var row = line.id() == null ? null : PageRows.rowOf(residentRows, line.id());
            if (row == null || row.getPage() != pageNumber || row.getPosition() != line.position()
                    || row.getLength() != line.length()) {
                return null;
            }
            rows.add(row);
        }
        return rows;
    }

    private VacuumedPage rewrite(String dbName, String collName, long pageNumber, RowsOfLines verified)
            throws IOException {
        final var page = paths.collectionPage(dbName, collName, pageNumber);
        final var subject = dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
        barrier.settle(dbName, collName);
        final var bytes = Files.readAllBytes(page.toPath());
        final var lines = PageLines.recordsOf(bytes, 0L);
        final var pageRows = verified.rowsOf(lines);
        final var begun = barrier.begin(dbName, collName, pageNumber, bytes.length);
        faultInjector.at(FaultPoint.WAL_REWRITE_BEGUN, subject);
        if (lines.isEmpty() && pageRows.isEmpty()) {
            return truncate(begun, page);
        }
        final var intent = journal.begin(dbName, collName, List.of(new WriteIntent.Vacuum(pageNumber)));
        final var temp = paths.vacuumFile(dbName, collName, pageNumber);
        final var moves = new LinkedHashMap<String, VacuumedPage.Move>();
        final long lengthAfter;
        try {
            lengthAfter = writeLiveRecords(temp, bytes, lines, moves);
            FileLocks.moveIntoPlaceUnsynced(temp.toPath(), page.toPath());
        } catch (IOException e) {
            Files.deleteIfExists(temp.toPath());
            journal.end(intent);
            throw e;
        }
        final var landed = barrier.landed(begun, page.toPath().toAbsolutePath().getParent());
        faultInjector.at(FaultPoint.PAGE_VACUUM_RENAMED, subject);
        final var vacuumed = new VacuumedPage(dbName, collName, pageNumber, moves);
        listener.accept(vacuumed);
        final var moved = movedRows(dbName, collName, pageNumber, pageRows, moves);
        try {
            if (landed) {
                barrier.end(begun, lengthAfter, moved.isEmpty());
            }
            pkIndexStore.appendReplacements(dbName, collName, moved);
        } catch (IOException e) {
            logger.error("Vacuumed " + page.getName() + " but could not record where its records moved; its intent is"
                    + " kept and the next startup re-derives the rows from the page", e);
            throw e;
        }
        journal.end(intent);
        return vacuumed;
    }

    private VacuumedPage truncate(WalRewriteBarrier.Begun begun, File page) throws IOException {
        if (!FileLocks.truncateTo(page, 0L, "Could not truncate the emptied page " + page.getPath())) {
            throw new IOException("Could not truncate the emptied page " + page.getName());
        }
        final var vacuumed = new VacuumedPage(begun.dbName(), begun.collName(), begun.page(), Map.of());
        listener.accept(vacuumed);
        barrier.end(begun, 0L, true);
        return vacuumed;
    }

    @FunctionalInterface
    private interface RowsOfLines {
        List<PkIndexEntry> rowsOf(List<PageLines.Line> lines) throws IOException;
    }

    private static void requireEveryLineNamed(File page, List<PageLines.Line> lines, List<PkIndexEntry> pageRows)
            throws IOException {
        final var byPosition = new HashMap<Long, PkIndexEntry>(pageRows.size() * 2);
        for (final var row : pageRows) {
            byPosition.put(row.getPosition(), row);
        }
        for (final var line : lines) {
            final var row = byPosition.get(line.position());
            if (row == null || row.getLength() != line.length() || !row.getValue().equals(line.id())) {
                throw new IOException("Refusing to vacuum " + page.getName() + ": the record at offset "
                        + line.position() + " is not the one its PK index names there; run REINDEX with"
                        + " \"primaryKey\": true on this collection");
            }
        }
        if (lines.size() != pageRows.size()) {
            throw new IOException("Refusing to vacuum " + page.getName() + ": its PK index names " + pageRows.size()
                    + " record(s) on it but the page holds " + lines.size()
                    + "; run REINDEX with \"primaryKey\": true on this collection");
        }
    }

    private static long writeLiveRecords(File temp, byte[] bytes, List<PageLines.Line> lines,
            LinkedHashMap<String, VacuumedPage.Move> moves) throws IOException {
        OwnerOnly.ensureOwnerOnlyFile(temp.toPath());
        try (var stream = new FileOutputStream(temp); var out = new BufferedOutputStream(stream, Globals.BUFFER_SIZE)) {
            var next = 0L;
            for (final var line : lines) {
                out.write(bytes, (int) line.position(), line.length());
                if (line.position() != next) {
                    moves.put(line.id(), new VacuumedPage.Move(line.position(), next));
                }
                next += line.length();
            }
            out.flush();
            SyncedWrites.content(stream.getChannel(), temp.toPath());
            return next;
        }
    }

    private static List<PkIndexEntry> movedRows(String dbName, String collName, long pageNumber,
            List<PkIndexEntry> pageRows, LinkedHashMap<String, VacuumedPage.Move> moves) {
        final var moved = new ArrayList<PkIndexEntry>(moves.size());
        for (final var row : pageRows) {
            final var move = moves.get(row.getValue());
            if (move != null) {
                moved.add(new PkIndexEntry(dbName, collName, row.getValue(), move.to(), row.getLength(), pageNumber,
                        row.getVersion()));
            }
        }
        return moved;
    }
}
