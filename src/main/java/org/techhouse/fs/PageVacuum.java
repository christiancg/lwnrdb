package org.techhouse.fs;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
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
    private volatile Consumer<VacuumedPage> listener = _ -> {
    };

    PageVacuum(FilePaths paths, PkIndexStore pkIndexStore, WriteIntentJournal journal) {
        this.paths = paths;
        this.pkIndexStore = pkIndexStore;
        this.journal = journal;
    }

    void onVacuum(Consumer<VacuumedPage> vacuumListener) {
        this.listener = vacuumListener;
    }

    VacuumedPage vacuumIfNeeded(String dbName, String collName, long pageNumber, long appendedBytes,
            PagePlacement placement, List<PkIndexEntry> residentRows) throws IOException {
        if (placement == PagePlacement.APPEND) {
            return null;
        }
        final var page = paths.collectionPage(dbName, collName, pageNumber);
        final var fileLength = page.exists() ? page.length() : 0L;
        final var maxPageSize = configuration.getMaxPageSize();
        if (placement == null && fileLength + appendedBytes <= maxPageSize) {
            return null;
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
        final var bytes = Files.readAllBytes(page.toPath());
        final var lines = PageLines.recordsOf(bytes, 0L);
        requireEveryLineNamed(page, lines, pageRows);
        final var intent = journal.begin(dbName, collName, List.of(new WriteIntent.Vacuum(pageNumber)));
        final var temp = paths.vacuumFile(dbName, collName, pageNumber);
        final var moves = new LinkedHashMap<String, VacuumedPage.Move>();
        try {
            writeLiveRecords(temp, bytes, lines, moves);
            FileLocks.moveIntoPlace(temp.toPath(), page.toPath());
        } catch (IOException e) {
            Files.deleteIfExists(temp.toPath());
            journal.end(intent);
            throw e;
        }
        faultInjector.at(FaultPoint.PAGE_VACUUM_RENAMED, dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName);
        final var vacuumed = new VacuumedPage(dbName, collName, pageNumber, moves);
        listener.accept(vacuumed);
        try {
            pkIndexStore.appendReplacements(dbName, collName, movedRows(dbName, collName, pageNumber, pageRows, moves));
        } catch (IOException e) {
            logger.error("Vacuumed " + page.getName() + " but could not record where its records moved; its intent is"
                    + " kept and the next startup re-derives the rows from the page", e);
            throw e;
        }
        journal.end(intent);
        return vacuumed;
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

    private static void writeLiveRecords(File temp, byte[] bytes, List<PageLines.Line> lines,
            LinkedHashMap<String, VacuumedPage.Move> moves) throws IOException {
        OwnerOnly.ensureOwnerOnlyFile(temp.toPath());
        try (var out = new BufferedOutputStream(Files.newOutputStream(temp.toPath(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), Globals.BUFFER_SIZE)) {
            var next = 0L;
            for (final var line : lines) {
                out.write(bytes, (int) line.position(), line.length());
                if (line.position() != next) {
                    moves.put(line.id(), new VacuumedPage.Move(line.position(), next));
                }
                next += line.length();
            }
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
