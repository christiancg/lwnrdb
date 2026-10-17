package org.techhouse.fs;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

final class RecordReplacer {
    private static final Logger logger = Logger.logFor(RecordReplacer.class);
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);

    private final FilePaths paths;
    private final PkIndexStore pkIndexStore;
    private final WriteIntentJournal journal;
    private final PageVacuum vacuum;

    RecordReplacer(FilePaths paths, PkIndexStore pkIndexStore, WriteIntentJournal journal, PageVacuum vacuum) {
        this.paths = paths;
        this.pkIndexStore = pkIndexStore;
        this.journal = journal;
        this.vacuum = vacuum;
    }

    PkIndexEntry replace(DbEntry entry, byte[] line, PkIndexEntry oldRow, long targetPage, PagePlacement placement,
            List<PkIndexEntry> residentRows) throws IOException {
        final var dbName = oldRow.getDatabaseName();
        final var collName = oldRow.getCollectionName();
        final var source = paths.collectionPage(dbName, collName, oldRow.getPage());
        final var target = paths.collectionPage(dbName, collName, targetPage);
        final var locks = lockInPageOrder(oldRow.getPage(), source, targetPage, target);
        try {
            var oldPosition = oldRow.getPosition();
            final var vacuumed = vacuum.vacuumIfNeeded(dbName, collName, targetPage, line.length, placement,
                    residentRows);
            if (vacuumed != null && targetPage == oldRow.getPage()) {
                oldPosition = positionAfter(vacuumed, oldRow.getValue(), oldPosition);
            }
            requireWithinPage(oldRow, oldPosition, source.length());
            final var targetLengthBefore = target.exists() ? target.length() : 0L;
            final var intent = journal.begin(dbName, collName,
                    List.of(WriteIntent.Replace.of(oldRow, oldPosition, targetPage, targetLengthBefore)));
            final PkIndexEntry newRow;
            try {
                final var separator = separatorBytes(target);
                appendToPage(target, separator, line);
                faultInjector.at(FaultPoint.PAGE_COPY_APPENDED, subject(dbName, collName));
                newRow = new PkIndexEntry(dbName, collName, oldRow.getValue(), targetLengthBefore + separator.length,
                        line.length, targetPage, entry.getVersion());
                pkIndexStore.appendReplacements(dbName, collName, List.of(newRow));
            } catch (IOException e) {
                cutBack(target, targetLengthBefore);
                journal.end(intent);
                throw e;
            }
            faultInjector.at(FaultPoint.PK_ROW_COMMITTED, subject(dbName, collName));
            entry.setPreviousByteSize(oldRow.getLength());
            blankCommitted(intent, Map.of(source, new long[][]{{oldPosition, oldRow.getLength()}}));
            return newRow;
        } finally {
            unlock(locks);
        }
    }

    void delete(PkIndexEntry oldRow) throws IOException {
        final var dbName = oldRow.getDatabaseName();
        final var collName = oldRow.getCollectionName();
        final var page = paths.collectionPage(dbName, collName, oldRow.getPage());
        final var lock = FileLocks.lockWrite(page);
        try {
            requireWithinPage(oldRow, oldRow.getPosition(), page.length());
            final var intent = journal.begin(dbName, collName, List.of(WriteIntent.Delete.of(oldRow)));
            try {
                pkIndexStore.appendDeletes(dbName, collName, List.of(oldRow.getValue()));
            } catch (IOException e) {
                journal.end(intent);
                throw e;
            }
            faultInjector.at(FaultPoint.PK_ROW_COMMITTED, subject(dbName, collName));
            blankCommitted(intent, Map.of(page, new long[][]{{oldRow.getPosition(), oldRow.getLength()}}));
        } finally {
            lock.unlock();
        }
    }

    List<IndexedDbEntry> bulkReplace(String dbName, String collName, List<IndexedDbEntry> entries,
            List<PkIndexEntry> residentRows, Function<? super IndexedDbEntry, byte[]> encodedLines) throws IOException {
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
            final var positions = positionsAfterVacuums(dbName, collName, entries, lines, groups, files, residentRows);
            final var lengthsBefore = new HashMap<Long, Long>();
            final var items = new ArrayList<WriteIntent>(entries.size());
            for (final var group : groups.entrySet()) {
                final var lengthBefore = files.get(group.getKey()).length();
                lengthsBefore.put(group.getKey(), lengthBefore);
                for (final var index : group.getValue()) {
                    final var oldRow = entries.get(index).getIndex();
                    items.add(WriteIntent.Replace.of(oldRow, positions[index], group.getKey(), lengthBefore));
                }
            }
            final var intent = journal.begin(dbName, collName, items);
            final var newRows = appendAndCommit(dbName, collName, entries, lines, groups, files, lengthsBefore, intent);
            faultInjector.at(FaultPoint.PK_ROW_COMMITTED, subject(dbName, collName));
            final var superseded = new HashMap<File, long[][]>();
            for (final var group : groups.entrySet()) {
                final var regions = new long[group.getValue().size()][];
                var at = 0;
                for (final var index : group.getValue()) {
                    regions[at++] = new long[]{positions[index], entries.get(index).getIndex().getLength()};
                }
                superseded.put(files.get(group.getKey()), regions);
            }
            blankCommitted(intent, superseded);
            return updatedEntries(dbName, collName, entries, newRows);
        } finally {
            for (var index = locks.size() - 1; index >= 0; index--) {
                locks.get(index).unlock();
            }
        }
    }

    private long[] positionsAfterVacuums(String dbName, String collName, List<IndexedDbEntry> entries, byte[][] lines,
            TreeMap<Long, List<Integer>> groups, TreeMap<Long, File> files, List<PkIndexEntry> residentRows)
            throws IOException {
        final var positions = new long[entries.size()];
        for (final var group : groups.entrySet()) {
            var appended = 0L;
            for (final var index : group.getValue()) {
                positions[index] = entries.get(index).getIndex().getPosition();
                appended += lines[index].length;
            }
            final var vacuumed = vacuum.vacuumIfNeeded(dbName, collName, group.getKey(), appended, null, residentRows);
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

    private PkIndexEntry[] appendAndCommit(String dbName, String collName, List<IndexedDbEntry> entries, byte[][] lines,
            TreeMap<Long, List<Integer>> groups, TreeMap<Long, File> files, Map<Long, Long> lengthsBefore,
            WriteIntentJournal.Intent intent) throws IOException {
        final var newRows = new PkIndexEntry[entries.size()];
        try {
            for (final var group : groups.entrySet()) {
                final var file = files.get(group.getKey());
                final var separator = separatorBytes(file);
                var offset = lengthsBefore.get(group.getKey()) + separator.length;
                OwnerOnly.ensureOwnerOnlyFile(file.toPath());
                try (var out = new BufferedOutputStream(new FileOutputStream(file, true), Globals.BUFFER_SIZE)) {
                    out.write(separator);
                    for (final var index : group.getValue()) {
                        final var entry = entries.get(index);
                        out.write(lines[index]);
                        newRows[index] = new PkIndexEntry(dbName, collName, entry.get_id(), offset, lines[index].length,
                                group.getKey(), entry.getVersion());
                        offset += lines[index].length;
                    }
                }
            }
            faultInjector.at(FaultPoint.PAGE_COPY_APPENDED, subject(dbName, collName));
            pkIndexStore.appendReplacements(dbName, collName, Arrays.asList(newRows));
            return newRows;
        } catch (IOException e) {
            for (final var page : groups.keySet()) {
                cutBack(files.get(page), lengthsBefore.get(page));
            }
            journal.end(intent);
            throw e;
        }
    }

    private static List<IndexedDbEntry> updatedEntries(String dbName, String collName, List<IndexedDbEntry> entries,
            PkIndexEntry[] newRows) {
        final var updated = new ArrayList<IndexedDbEntry>(entries.size());
        for (var index = 0; index < entries.size(); index++) {
            final var entry = entries.get(index);
            final var updatedEntry = new IndexedDbEntry();
            updatedEntry.setIndex(newRows[index]);
            updatedEntry.set_id(entry.get_id());
            updatedEntry.setCollectionName(collName);
            updatedEntry.setDatabaseName(dbName);
            updatedEntry.setData(entry.getData());
            updatedEntry.setVersion(newRows[index].getVersion());
            updatedEntry.setPreviousByteSize(entry.getIndex().getLength());
            updated.add(updatedEntry);
        }
        return updated;
    }

    private static void cutBack(File page, long lengthBefore) {
        if (page.length() != lengthBefore) {
            FileLocks.truncateTo(page, lengthBefore);
        }
    }

    static long positionAfter(VacuumedPage vacuumed, String id, long position) {
        final var move = vacuumed.moves().get(id);
        return move != null && move.from() == position ? move.to() : position;
    }

    private void blankCommitted(WriteIntentJournal.Intent intent, Map<File, long[][]> regionsByPage) {
        for (final var page : regionsByPage.entrySet()) {
            try (var channel = FileChannel.open(page.getKey().toPath(), StandardOpenOption.WRITE)) {
                for (final var region : page.getValue()) {
                    PageHoles.blank(channel, region[0], region[1]);
                }
            } catch (IOException e) {
                logger.error("Committed a write to " + page.getKey().getName() + " but could not blank the copies it"
                        + " superseded; its intent is kept and the next startup blanks them", e);
                return;
            }
        }
        journal.end(intent);
    }

    static void requireWithinPage(PkIndexEntry entry, long position, long pageLength) throws IOException {
        if (position + entry.getLength() > pageLength) {
            throw new IOException("The PK index places '" + entry.getValue() + "' at " + position + "+"
                    + entry.getLength() + " on page " + entry.getPage() + ", past its " + pageLength
                    + "-byte end; refusing to write around a record that is not there");
        }
    }

    static String subject(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }

    static byte[] separatorBytes(File file) throws IOException {
        return FileLocks.separatorBeforeAppend(file).getBytes(StandardCharsets.UTF_8);
    }

    private static void appendToPage(File file, byte[] separator, byte[] line) throws IOException {
        OwnerOnly.ensureOwnerOnlyFile(file.toPath());
        try (var out = new FileOutputStream(file, true)) {
            if (separator.length > 0) {
                out.write(separator);
            }
            out.write(line);
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
