package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.function.LongFunction;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fault.FaultPoint;
import org.techhouse.log.Logger;

final class RecordReplacer {
    private static final Logger logger = Logger.logFor(RecordReplacer.class);
    private final Configuration configuration = Configuration.getInstance();
    private final FilePaths paths;
    private final PkIndexStore pkIndexStore;
    private final PageVacuum vacuum;

    RecordReplacer(FilePaths paths, PkIndexStore pkIndexStore, PageVacuum vacuum) {
        this.paths = paths;
        this.pkIndexStore = pkIndexStore;
        this.vacuum = vacuum;
    }

    PkIndexEntry replace(DbEntry entry, byte[] line, PkIndexEntry oldRow, long targetPage, PagePlacement placement)
            throws IOException {
        return replace(entry, line, oldRow, targetPage, placement, null, null);
    }

    PkIndexEntry replace(DbEntry entry, byte[] line, PkIndexEntry oldRow, long targetPage, PagePlacement placement,
            List<PkIndexEntry> residentRows, LongFunction<PageOccupancy> occupancy) throws IOException {
        final var dbName = oldRow.getDatabaseName();
        final var collName = oldRow.getCollectionName();
        final var source = paths.collectionPage(dbName, collName, oldRow.getPage());
        final var target = paths.collectionPage(dbName, collName, targetPage);
        final var locks = lockInPageOrder(oldRow.getPage(), source, targetPage, target);
        try {
            var oldPosition = oldRow.getPosition();
            final var vacuumed = vacuum.vacuumIfNeeded(dbName, collName, targetPage, line.length, placement,
                    residentRows, occupancy);
            if (vacuumed != null && targetPage == oldRow.getPage()) {
                oldPosition = positionAfter(vacuumed, oldRow.getValue(), oldPosition);
            }
            requireWithinPage(oldRow, oldPosition, source.length());
            final var plan = new WalPlan(paths, pkIndexStore, dbName, collName);
            final var newRow = new PkIndexEntry(dbName, collName, oldRow.getValue(), plan.append(targetPage, line),
                    line.length, targetPage, entry.getVersion());
            plan.replaceRow(newRow);
            plan.blank(oldRow.getPage(), oldPosition, oldRow.getLength());
            plan.commit(FaultPoint.PAGE_COPY_APPENDED, FaultPoint.PK_ROW_COMMITTED);
            entry.setPreviousByteSize(oldRow.getLength());
            return newRow;
        } finally {
            unlock(locks);
        }
    }

    void delete(PkIndexEntry oldRow) throws IOException {
        delete(oldRow, null, null);
    }

    void delete(PkIndexEntry oldRow, List<PkIndexEntry> residentRows, LongFunction<PageOccupancy> occupancy)
            throws IOException {
        final var dbName = oldRow.getDatabaseName();
        final var collName = oldRow.getCollectionName();
        final var page = paths.collectionPage(dbName, collName, oldRow.getPage());
        final var lock = FileLocks.lockWrite(page);
        try {
            final var fileLength = page.length();
            requireWithinPage(oldRow, oldRow.getPosition(), fileLength);
            final var before = residentRows == null || occupancy == null ? null : occupancy.apply(oldRow.getPage());
            final var plan = new WalPlan(paths, pkIndexStore, dbName, collName);
            plan.deleteRow(oldRow.getValue());
            plan.blank(oldRow.getPage(), oldRow.getPosition(), oldRow.getLength());
            plan.commit(null, FaultPoint.PK_ROW_COMMITTED);
            if (before != null) {
                vacuumAfterDelete(dbName, collName, oldRow.getPage(), residentRows,
                        new PageOccupancy(before.liveBytes() - oldRow.getLength(), before.rows() - 1), page, fileLength,
                        oldRow.getValue());
            }
        } finally {
            lock.unlock();
        }
    }

    void vacuumAfterDeletes(String dbName, String collName, Collection<Long> pages, List<PkIndexEntry> residentRows,
            LongFunction<PageOccupancy> occupancy) {
        for (final var pageNumber : new TreeSet<>(pages)) {
            final var page = paths.collectionPage(dbName, collName, pageNumber);
            final var lock = FileLocks.lockWrite(page);
            try {
                final var after = occupancy.apply(pageNumber);
                if (after != null) {
                    vacuumAfterDelete(dbName, collName, pageNumber, residentRows, after, page, page.length(), null);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void vacuumAfterDelete(String dbName, String collName, long pageNumber, List<PkIndexEntry> residentRows,
            PageOccupancy after, File page, long fileLength, String deletedId) {
        if (!PagePlacement.vacuumsAfterDelete(after.liveBytes(), fileLength, configuration.getMaxPageSize())) {
            return;
        }
        try {
            vacuum.vacuumAfterDelete(dbName, collName, pageNumber, residentRows, after, deletedId);
        } catch (IOException e) {
            logger.warning("Deleted " + (deletedId == null ? "records" : "'" + deletedId + "'") + " from "
                    + page.getName() + " but could not vacuum the page after it; the delete stands and a later write"
                    + " vacuums the page: " + e.getMessage());
        }
    }

    List<IndexedDbEntry> bulkReplace(String dbName, String collName, List<IndexedDbEntry> entries,
            List<PkIndexEntry> residentRows, Function<? super IndexedDbEntry, byte[]> encodedLines,
            LongFunction<PageOccupancy> occupancy) throws IOException {
        if (entries.isEmpty()) {
            return List.of();
        }
        final var lines = new byte[entries.size()][];
        final var groups = new TreeMap<Long, List<Integer>>();
        for (var index = 0; index < entries.size(); index++) {
            final var entry = entries.get(index);
            final var line = encodedLines == null ? null : encodedLines.apply(entry);
            lines[index] = line == null ? entry.toDbEntry().encodeLine() : line;
            groups.computeIfAbsent(entry.getIndex().getPage(), _ -> new ArrayList<>()).add(index);
        }
        final var files = new TreeMap<Long, File>();
        final var locks = new ArrayList<Lock>(groups.size());
        for (final var page : groups.keySet()) {
            final var file = paths.collectionPage(dbName, collName, page);
            files.put(page, file);
            locks.add(FileLocks.lockWrite(file));
        }
        try {
            final var positions = positionsAfterVacuums(dbName, collName, entries, lines, groups, files, residentRows,
                    occupancy);
            final var plan = new WalPlan(paths, pkIndexStore, dbName, collName);
            final var newRows = new PkIndexEntry[entries.size()];
            for (final var group : groups.entrySet()) {
                final var page = group.getKey();
                for (final var index : group.getValue()) {
                    final var entry = entries.get(index);
                    newRows[index] = new PkIndexEntry(dbName, collName, entry.getIndex().getValue(),
                            plan.append(page, lines[index]), lines[index].length, page, entry.getVersion());
                    plan.replaceRow(newRows[index]);
                    plan.blank(page, positions[index], entry.getIndex().getLength());
                }
            }
            plan.commit(FaultPoint.PAGE_COPY_APPENDED, FaultPoint.PK_ROW_COMMITTED);
            return updatedEntries(dbName, collName, entries, newRows);
        } finally {
            for (var index = locks.size() - 1; index >= 0; index--) {
                locks.get(index).unlock();
            }
        }
    }

    private long[] positionsAfterVacuums(String dbName, String collName, List<IndexedDbEntry> entries, byte[][] lines,
            TreeMap<Long, List<Integer>> groups, TreeMap<Long, File> files, List<PkIndexEntry> residentRows,
            LongFunction<PageOccupancy> occupancy) throws IOException {
        final var positions = new long[entries.size()];
        for (final var group : groups.entrySet()) {
            var appended = 0L;
            for (final var index : group.getValue()) {
                positions[index] = entries.get(index).getIndex().getPosition();
                appended += lines[index].length;
            }
            final var vacuumed = vacuum.vacuumIfNeeded(dbName, collName, group.getKey(), appended, null, residentRows,
                    occupancy);
            final var pageLength = files.get(group.getKey()).length();
            for (final var index : group.getValue()) {
                final var oldRow = entries.get(index).getIndex();
                if (vacuumed != null) {
                    positions[index] = positionAfter(vacuumed, oldRow.getValue(), positions[index]);
                }
                requireWithinPage(oldRow, positions[index], pageLength);
            }
        }
        return positions;
    }

    private static List<IndexedDbEntry> updatedEntries(String dbName, String collName, List<IndexedDbEntry> entries,
            PkIndexEntry[] newRows) {
        final var updated = new ArrayList<IndexedDbEntry>(entries.size());
        for (var index = 0; index < entries.size(); index++) {
            final var entry = entries.get(index);
            final var updatedEntry = new IndexedDbEntry();
            updatedEntry.setIndex(newRows[index]);
            updatedEntry.set_id(newRows[index].getValue());
            updatedEntry.setCollectionName(collName);
            updatedEntry.setDatabaseName(dbName);
            updatedEntry.setData(entry.getData());
            updatedEntry.setVersion(newRows[index].getVersion());
            updatedEntry.setPreviousByteSize(entry.getIndex().getLength());
            updated.add(updatedEntry);
        }
        return updated;
    }

    static long positionAfter(VacuumedPage vacuumed, String id, long position) {
        final var move = vacuumed.moves().get(id);
        return move != null && move.from() == position ? move.to() : position;
    }

    static void requireWithinPage(PkIndexEntry entry, long position, long pageLength) throws IOException {
        if (position + entry.getLength() > pageLength) {
            throw new IOException("The PK index places '" + entry.getValue() + "' at " + position + "+"
                    + entry.getLength() + " on page " + entry.getPage() + ", past its " + pageLength
                    + "-byte end; refusing to write around a record that is not there");
        }
    }

    private static Lock[] lockInPageOrder(long firstPage, File first, long secondPage, File second) {
        if (firstPage == secondPage) {
            return new Lock[]{FileLocks.lockWrite(first)};
        }
        final var lowerLock = FileLocks.lockWrite(firstPage < secondPage ? first : second);
        return new Lock[]{lowerLock, FileLocks.lockWrite(firstPage < secondPage ? second : first)};
    }

    private static void unlock(Lock[] locks) {
        for (var index = locks.length - 1; index >= 0; index--) {
            locks[index].unlock();
        }
    }
}
