package org.techhouse.fs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fault.FaultPoint;

final class PageAppender {
    private final FilePaths paths;
    private final PkIndexStore pkIndexStore;
    private final PageVacuum vacuum;

    PageAppender(FilePaths paths, PkIndexStore pkIndexStore, PageVacuum vacuum) {
        this.paths = paths;
        this.pkIndexStore = pkIndexStore;
        this.vacuum = vacuum;
    }

    <T extends DbEntry> List<IndexedDbEntry> bulkInsert(String dbName, String collName, List<T> entries,
            Function<? super T, Long> storagePageResolver, Function<? super T, byte[]> encodedLines)
            throws IOException {
        final var groups = entries.stream()
                .collect(Collectors.groupingBy(storagePageResolver, TreeMap::new, Collectors.toList()));
        final var locks = new ArrayList<Lock>(groups.size());
        try {
            for (final var group : groups.entrySet()) {
                locks.add(FileLocks.lockWrite(paths.collectionPage(dbName, collName, group.getKey())));
                vacuum.vacuumIfNeeded(dbName, collName, group.getKey(), appendedBytes(group.getValue()), null, null);
            }
            final var plan = new WalPlan(paths, pkIndexStore, dbName, collName);
            final var indexEntries = new ArrayList<IndexedDbEntry>(entries.size());
            for (final var group : groups.entrySet()) {
                for (final var entry : group.getValue()) {
                    final var line = encodedLines.apply(entry);
                    final var pkEntry = new PkIndexEntry(dbName, collName, entry.get_id(),
                            plan.append(group.getKey(), line), line.length, group.getKey(), entry.getVersion());
                    plan.insertRow(pkEntry);
                    indexEntries.add(indexed(dbName, collName, entry, pkEntry));
                }
            }
            plan.commit();
            return indexEntries;
        } finally {
            for (var index = locks.size() - 1; index >= 0; index--) {
                locks.get(index).unlock();
            }
        }
    }

    <T extends DbEntry> List<IndexedDbEntry> bulkInsertAndDelete(String dbName, String collName, List<T> inserts,
            Function<? super T, Long> pageOf, Function<? super T, byte[]> lines, List<PkIndexEntry> deletes)
            throws IOException {
        final var groups = inserts.stream().collect(Collectors.groupingBy(pageOf, TreeMap::new, Collectors.toList()));
        final var pages = new TreeSet<>(groups.keySet());
        final var positions = new long[deletes.size()];
        for (var index = 0; index < positions.length; index++) {
            pages.add(deletes.get(index).getPage());
            positions[index] = deletes.get(index).getPosition();
        }
        final var locks = new ArrayList<Lock>(pages.size());
        try {
            for (final var page : pages) {
                locks.add(FileLocks.lockWrite(paths.collectionPage(dbName, collName, page)));
            }
            final var vacuumed = new HashMap<Long, VacuumedPage>();
            for (final var group : groups.entrySet()) {
                final var moved = vacuum.vacuumIfNeeded(dbName, collName, group.getKey(),
                        appendedBytes(group.getValue()), null, null);
                if (moved != null) {
                    vacuumed.put(group.getKey(), moved);
                }
            }
            final var plan = new WalPlan(paths, pkIndexStore, dbName, collName);
            for (var index = 0; index < positions.length; index++) {
                final var row = deletes.get(index);
                final var moved = vacuumed.get(row.getPage());
                final var position = moved == null
                        ? positions[index]
                        : RecordReplacer.positionAfter(moved, row.getValue(), positions[index]);
                RecordReplacer.requireWithinPage(row, position,
                        paths.collectionPage(dbName, collName, row.getPage()).length());
                plan.deleteRow(row.getValue());
                plan.blank(row.getPage(), position, row.getLength());
            }
            final var indexEntries = new ArrayList<IndexedDbEntry>(inserts.size());
            for (final var group : groups.entrySet()) {
                for (final var entry : group.getValue()) {
                    final var line = lines.apply(entry);
                    final var pkEntry = new PkIndexEntry(dbName, collName, entry.get_id(),
                            plan.append(group.getKey(), line), line.length, group.getKey(), entry.getVersion());
                    plan.insertRow(pkEntry);
                    indexEntries.add(indexed(dbName, collName, entry, pkEntry));
                }
            }
            plan.commit(null, FaultPoint.PK_ROW_COMMITTED);
            return indexEntries;
        } finally {
            for (var index = locks.size() - 1; index >= 0; index--) {
                locks.get(index).unlock();
            }
        }
    }

    private static long appendedBytes(List<? extends DbEntry> entries) {
        var bytes = 0L;
        for (final var entry : entries) {
            bytes += entry.byteSize();
        }
        return bytes;
    }

    private static IndexedDbEntry indexed(String dbName, String collName, DbEntry entry, PkIndexEntry pkEntry) {
        final var indexedEntry = new IndexedDbEntry();
        indexedEntry.setIndex(pkEntry);
        indexedEntry.setCollectionName(collName);
        indexedEntry.setDatabaseName(dbName);
        indexedEntry.set_id(entry.get_id());
        indexedEntry.setData(entry.getData());
        indexedEntry.setVersion(entry.getVersion());
        return indexedEntry;
    }

    PkIndexEntry insert(DbEntry entry, byte[] line) throws IOException {
        final var dbName = entry.getDatabaseName();
        final var collName = entry.getCollectionName();
        final var page = entry.getPage();
        final var lock = FileLocks.lockWrite(paths.collectionPage(dbName, collName, page));
        try {
            vacuum.vacuumIfNeeded(dbName, collName, page, line.length, null, null);
            final var plan = new WalPlan(paths, pkIndexStore, dbName, collName);
            final var row = new PkIndexEntry(dbName, collName, entry.get_id(), plan.append(page, line), line.length,
                    page, entry.getVersion());
            plan.insertRow(row);
            plan.commit();
            return row;
        } finally {
            lock.unlock();
        }
    }
}
