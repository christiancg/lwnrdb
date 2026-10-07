package org.techhouse.ops.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private PageOccupancyReconciler() {
    }

    public static void reconcileAll() {
        healAdminPageTails();
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
            final var data = new JsonObject();
            data.addProperty(Globals.PK_FIELD, deleted.getValue());
            final var entry = DbEntry.fromJsonObject(dbName, collName, data);
            entry.setPage(deleted.getPage());
            byCollection.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName), _ -> new ArrayList<>())
                    .add(entry);
        }
        for (final var entries : byCollection.values()) {
            final var first = entries.getFirst();
            scheduleIndexMaintenanceFor(first.getDatabaseName(), first.getCollectionName(), entries);
        }
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

    private static String pageRowCollectionOf(String dbName, String collName) {
        return String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
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
        fs.healTornPageTails(dbName, collName);
        final var adopted = fs.adoptOrphanedRecords(dbName, collName);
        if (!adopted.isEmpty()) {
            scheduleIndexMaintenanceFor(dbName, collName, adopted);
        }
        final var fileLengths = fs.pageFileLengths(dbName, collName);
        final var rows = rowsByPage(cache.getAdminPageEntries(dbName, collName));
        if (adopted.isEmpty() && agreesWithFiles(rows, fileLengths)) {
            return false;
        }
        final var counts = entryCountsByPage(dbName, collName);
        final var corrected = new ArrayList<AdminPageEntry>();
        for (final var file : fileLengths.entrySet()) {
            corrected.add(row(dbName, collName, file.getKey(), counts.getOrDefault(file.getKey(), 0), file.getValue()));
        }
        for (final var page : rows.keySet()) {
            if (!fileLengths.containsKey(page)) {
                corrected.add(row(dbName, collName, page, 0, 0L));
            }
        }
        AdminPageHelper.replacePageEntries(dbName, collName, corrected);
        return true;
    }

    private static void scheduleIndexMaintenanceFor(String dbName, String collName, List<DbEntry> adopted) {
        final var generation = pendingIndexWrites.mark(dbName, collName,
                adopted.stream().map(DbEntry::get_id).toList());
        final var incarnation = CollectionIncarnation.current(dbName, collName);
        for (final var entry : adopted) {
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

    private static boolean agreesWithFiles(Map<Long, AdminPageEntry> rows, Map<Long, Long> fileLengths) {
        for (final var file : fileLengths.entrySet()) {
            final var row = rows.get(file.getKey());
            final var recordedSize = row == null ? 0L : row.getPageSize();
            if (recordedSize != file.getValue()) {
                return false;
            }
        }
        for (final var row : rows.values()) {
            if (!fileLengths.containsKey(row.getPage()) && (row.getPageSize() != 0 || row.getEntryCount() != 0)) {
                return false;
            }
        }
        return true;
    }

    private static Map<Long, Integer> entryCountsByPage(String dbName, String collName) throws Exception {
        final var counts = new HashMap<Long, Integer>();
        for (final var pkEntry : fs.readWholePkIndexFile(dbName, collName)) {
            counts.merge(pkEntry.getPage(), 1, Integer::sum);
        }
        return counts;
    }

    private static AdminPageEntry row(String dbName, String collName, long page, int entryCount, long pageSize) {
        final var entry = new AdminPageEntry(dbName, collName, page);
        entry.setEntryCount(entryCount);
        entry.setPageSize(pageSize);
        return entry;
    }
}
