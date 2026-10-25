package org.techhouse.ops.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public final class PageOccupancyReconciler {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final Logger logger = Logger.logFor(PageOccupancyReconciler.class);
    private static final int ENTRY_COUNT = 0;
    private static final int LIVE_BYTES = 1;
    private static final long[] NOTHING_LIVE = new long[2];

    public record ReplayedCollection(String dbName, String collName, List<PkIndexEntry> rows, long generation) {
    }

    private PageOccupancyReconciler() {
    }

    public static void reconcileAll() {
        healAdminPageTails();
        reconcileAdminRows();
        for (final var dbEntry : List.copyOf(cache.getAllAdminDbEntries())) {
            for (final var collName : cache.getCollectionNamesForDatabase(dbEntry.get_id())) {
                reconcileQuietly(dbEntry.get_id(), collName);
            }
        }
    }

    public static void scheduleIndexCleanupFor(List<PkIndexEntry> completedDeletes) {
        final var byCollection = new LinkedHashMap<String, List<DbEntry>>();
        for (final var deleted : completedDeletes) {
            final var dbName = deleted.getDatabaseName();
            final var collName = deleted.getCollectionName();
            if (isAdminDatabase(dbName) || cache.getAdminCollectionEntry(dbName, collName) == null) {
                continue;
            }
            byCollection.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName), _ -> new ArrayList<>())
                    .add(stubOf(deleted));
        }
        for (final var entries : byCollection.values()) {
            final var first = entries.getFirst();
            scheduleIndexMaintenanceFor(first.getDatabaseName(), first.getCollectionName(), entries);
        }
    }

    public static List<ReplayedCollection> markReplayed(List<PkIndexEntry> written) {
        final var byCollection = new LinkedHashMap<String, List<PkIndexEntry>>();
        for (final var row : written) {
            if (!isAdminDatabase(row.getDatabaseName())) {
                byCollection
                        .computeIfAbsent(Cache.getCollectionIdentifier(row.getDatabaseName(), row.getCollectionName()),
                                _ -> new ArrayList<>())
                        .add(row);
            }
        }
        final var replayed = new ArrayList<ReplayedCollection>(byCollection.size());
        for (final var rows : byCollection.values()) {
            final var dbName = rows.getFirst().getDatabaseName();
            final var collName = rows.getFirst().getCollectionName();
            replayed.add(new ReplayedCollection(dbName, collName, rows,
                    pendingIndexWrites.mark(dbName, collName, idsOf(rows))));
        }
        return replayed;
    }

    public static void scheduleIndexRepairFor(List<ReplayedCollection> replayed) {
        for (final var collection : replayed) {
            final var dbName = collection.dbName();
            final var collName = collection.collName();
            if (cache.getAdminCollectionEntry(dbName, collName) == null) {
                pendingIndexWrites.clear(dbName, collName, idsOf(collection.rows()), collection.generation());
                continue;
            }
            submitUpdates(dbName, collName, collection.rows().stream().map(PageOccupancyReconciler::stubOf).toList(),
                    collection.generation());
        }
    }

    private static List<String> idsOf(List<PkIndexEntry> rows) {
        return rows.stream().map(PkIndexEntry::getValue).toList();
    }

    private static DbEntry stubOf(PkIndexEntry row) {
        final var data = new JsonObject();
        data.addProperty(Globals.PK_FIELD, row.getValue());
        final var entry = DbEntry.fromJsonObject(row.getDatabaseName(), row.getCollectionName(), data);
        entry.setPage(row.getPage());
        return entry;
    }

    private static boolean isAdminDatabase(String dbName) {
        return Globals.ADMIN_DB_NAME.equals(dbName) || Globals.ADMIN_PAGES_DB_NAME.equals(dbName);
    }

    private static void healAdminPageTails() {
        for (final var collName : Globals.ADMIN_COLLECTION_NAMES) {
            healQuietly(Globals.ADMIN_DB_NAME, collName);
            healQuietly(Globals.ADMIN_PAGES_DB_NAME, pageRowCollectionOf(Globals.ADMIN_DB_NAME, collName));
        }
    }

    private static void reconcileAdminRows() {
        for (final var collName : Globals.ADMIN_COLLECTION_NAMES) {
            try {
                if (reconcileAdminRows(collName)) {
                    logger.warning("Rebuilt the page-occupancy metadata of " + Globals.ADMIN_DB_NAME + "|" + collName
                            + " from its page files: they are written back, so an unclean stop leaves them behind");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.warning("Could not reconcile the page-occupancy metadata of " + Globals.ADMIN_DB_NAME + "|"
                        + collName + ": " + e.getMessage());
            }
        }
    }

    public static boolean reconcileAdminRows(String collName) throws Exception {
        final var dbName = Globals.ADMIN_DB_NAME;
        final var pages = fs.pageFileLengths(dbName, collName).keySet();
        final var occupancy = occupancyByPage(fs.readWholePkIndexFile(dbName, collName));
        final var rows = rowsByPage(cache.getAdminPageEntries(dbName, collName));
        if (agreesWithLiveBytes(rows, pages, occupancy) && countsAgree(rows, occupancy)) {
            return false;
        }
        replaceRows(dbName, collName, pages, occupancy, rows);
        return true;
    }

    private static boolean countsAgree(Map<Long, AdminPageEntry> rows, Map<Long, long[]> occupancy) {
        for (final var live : occupancy.entrySet()) {
            final var row = rows.get(live.getKey());
            if ((row == null ? 0L : row.getEntryCount()) != live.getValue()[ENTRY_COUNT]) {
                return false;
            }
        }
        for (final var row : rows.values()) {
            if (row.getEntryCount() != occupancy.getOrDefault(row.getPage(), NOTHING_LIVE)[ENTRY_COUNT]) {
                return false;
            }
        }
        return true;
    }

    private static String pageRowCollectionOf(String dbName, String collName) {
        return AdminPageEntry.pagesCollectionName(dbName, collName);
    }

    private static void healQuietly(String dbName, String collName) {
        try {
            fs.healTornPageTails(dbName, collName);
        } catch (Exception e) {
            logger.warning("Could not heal the page tails of " + dbName + "|" + collName + ": " + e.getMessage());
        }
    }

    private static void reconcileQuietly(String dbName, String collName) {
        try {
            if (reconcile(dbName, collName)) {
                logger.warning("Rebuilt the page-occupancy metadata of " + dbName + "|" + collName
                        + " from its page files: it no longer matched them, most likely after an unclean stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warning("Could not reconcile the page-occupancy metadata of " + dbName + "|" + collName + ": "
                    + e.getMessage());
        }
    }

    public static boolean reconcile(String dbName, String collName) throws Exception {
        fs.healTornPageTails(Globals.ADMIN_PAGES_DB_NAME, pageRowCollectionOf(dbName, collName));
        final var session = fs.pkRowsSession(dbName, collName);
        fs.healTornPageTails(session);
        final var retired = fs.retireDanglingPkEntries(session);
        if (!retired.isEmpty()) {
            fs.healTornPageTails(session);
            scheduleIndexCleanupFor(retired);
        }
        final var adopted = fs.adoptOrphanedRecords(session);
        if (!adopted.isEmpty()) {
            scheduleIndexMaintenanceFor(dbName, collName, adopted);
        }
        final var pages = fs.pageFileLengths(dbName, collName).keySet();
        final var occupancy = occupancyByPage(session.rows());
        final var rows = rowsByPage(cache.getAdminPageEntries(dbName, collName));
        if (adopted.isEmpty() && retired.isEmpty() && agreesWithLiveBytes(rows, pages, occupancy)
                && countsAgree(rows, occupancy)) {
            return false;
        }
        replaceRows(dbName, collName, pages, occupancy, rows);
        return true;
    }

    public static void rebuildRows(String dbName, String collName) throws Exception {
        replaceRows(dbName, collName, fs.pageFileLengths(dbName, collName).keySet(),
                occupancyByPage(fs.readWholePkIndexFile(dbName, collName)),
                rowsByPage(cache.getAdminPageEntries(dbName, collName)));
    }

    private static void replaceRows(String dbName, String collName, Set<Long> pages, Map<Long, long[]> occupancy,
            Map<Long, AdminPageEntry> rows) throws Exception {
        final var corrected = new ArrayList<AdminPageEntry>();
        for (final var page : pages) {
            final var live = occupancy.getOrDefault(page, NOTHING_LIVE);
            corrected.add(row(dbName, collName, page, (int) live[ENTRY_COUNT], live[LIVE_BYTES]));
        }
        for (final var page : rows.keySet()) {
            if (!pages.contains(page)) {
                corrected.add(row(dbName, collName, page, 0, 0L));
            }
        }
        AdminPageHelper.replacePageEntries(dbName, collName, corrected);
    }

    public static void scheduleIndexMaintenanceFor(String dbName, String collName, List<DbEntry> adopted) {
        submitUpdates(dbName, collName, adopted,
                pendingIndexWrites.markRecovered(dbName, collName, adopted.stream().map(DbEntry::get_id).toList()));
    }

    private static void submitUpdates(String dbName, String collName, List<DbEntry> entries, long generation) {
        final var incarnation = CollectionIncarnation.current(dbName, collName);
        for (final var entry : entries) {
            entry.setPreviousByteSize(entry.byteSize());
            taskManager.submitBackgroundTask(
                    new EntityEvent(EventType.UPDATED, dbName, collName, entry, incarnation, generation));
        }
    }

    private static Map<Long, AdminPageEntry> rowsByPage(List<AdminPageEntry> rows) {
        final var byPage = new HashMap<Long, AdminPageEntry>();
        if (rows != null) {
            for (final var row : rows) {
                byPage.put(row.getPage(), row);
            }
        }
        return byPage;
    }

    private static boolean agreesWithLiveBytes(Map<Long, AdminPageEntry> rows, Set<Long> pages,
            Map<Long, long[]> occupancy) {
        for (final var page : pages) {
            final var row = rows.get(page);
            final var recordedSize = row == null ? 0L : row.getPageSize();
            if (recordedSize != occupancy.getOrDefault(page, NOTHING_LIVE)[LIVE_BYTES]) {
                return false;
            }
        }
        for (final var row : rows.values()) {
            if (!pages.contains(row.getPage()) && (row.getPageSize() != 0 || row.getEntryCount() != 0)) {
                return false;
            }
        }
        return true;
    }

    private static Map<Long, long[]> occupancyByPage(List<PkIndexEntry> pkRows) {
        final var occupancy = new HashMap<Long, long[]>();
        for (final var pkEntry : pkRows) {
            final var live = occupancy.computeIfAbsent(pkEntry.getPage(), _ -> new long[2]);
            live[ENTRY_COUNT]++;
            live[LIVE_BYTES] += pkEntry.getLength();
        }
        return occupancy;
    }

    private static AdminPageEntry row(String dbName, String collName, long page, int entryCount, long pageSize) {
        final var entry = new AdminPageEntry(dbName, collName, page);
        entry.setEntryCount(entryCount);
        entry.setPageSize(pageSize);
        return entry;
    }
}
