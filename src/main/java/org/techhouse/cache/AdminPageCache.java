package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

final class AdminPageCache {
    private static final long INTERRUPTED_LOCK_BUDGET_MS = 250;
    private final Logger logger = Logger.logFor(AdminPageCache.class);
    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final org.techhouse.concurrency.ResourceLocking locks = IocContainer
            .get(org.techhouse.concurrency.ResourceLocking.class);
    private final Map<String, List<AdminPageEntry>> pages = new ConcurrentHashMap<>();
    private final Map<String, List<PkIndexEntry>> pagesPkIndexes = new ConcurrentHashMap<>();

    void loadAdminPagesForCollection(String dbName, String collName) throws IOException {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        final var pkIdx = fs.readWholePkIndexFile(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);
        pagesPkIndexes.put(pagesCollectionKey(pagesCollName), new ArrayList<>(pkIdx));
        final var pageEntries = new ArrayList<AdminPageEntry>();
        final var idPrefix = collId + Globals.COLL_IDENTIFIER_SEPARATOR;
        try (var pagesStream = fs.streamPages(Globals.ADMIN_PAGES_DB_NAME, pagesCollName)) {
            pagesStream.forEach(map -> map.values().stream()
                    .filter(e -> e.get_id() != null && e.get_id().startsWith(idPrefix))
                    .map(e -> AdminPageEntry.fromJsonObject(dbName, collName, e.getData())).forEach(pageEntries::add));
        }
        pages.put(collId, new CopyOnWriteArrayList<>(pageEntries));
        rebuildInMemoryPagesFromPkIndex(pagesCollName, pkIdx);
    }

    void rebuildInMemoryPagesFromPkIndex(String collName, List<PkIndexEntry> pkIdx) {
        final var byPage = pkIdx.stream().collect(Collectors.groupingBy(PkIndexEntry::getPage));
        final var entries = new ArrayList<AdminPageEntry>();
        for (var e : byPage.entrySet()) {
            final var pageNum = e.getKey();
            final var pkList = e.getValue();
            final var entry = new AdminPageEntry(Globals.ADMIN_PAGES_DB_NAME, collName, pageNum);
            entry.setEntryCount(pkList.size());
            entry.setPageSize(pkList.stream().mapToLong(PkIndexEntry::getLength).sum());
            entries.add(entry);
        }
        pages.put(pagesCollectionKey(collName), new CopyOnWriteArrayList<>(entries));
    }

    List<PkIndexEntry> pkIndexesForPagesCollection(String pagesCollName) {
        final var list = pagesPkIndexes.get(pagesCollectionKey(pagesCollName));
        return list != null ? list : List.of();
    }

    private static String pagesCollectionKey(String pagesCollName) {
        return Cache.getCollectionIdentifier(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);
    }

    List<AdminPageEntry> getAdminPageEntries(String dbName, String collName) {
        return pages.get(Cache.getCollectionIdentifier(dbName, collName));
    }

    AdminPageEntry getAdminPageEntry(String dbName, String collName, long page) {
        final var entries = pages.get(Cache.getCollectionIdentifier(dbName, collName));
        if (entries == null)
            return null;
        return findPage(entries, page);
    }

    void addAdminPageEntries(String dbName, String collName, AdminPageEntry adminPageEntry) {
        pageList(dbName, collName).add(adminPageEntry);
    }

    void addAdminPageEntries(String dbName, String collName, List<AdminPageEntry> adminPageEntries) {
        if (!adminPageEntries.isEmpty()) {
            pageList(dbName, collName).addAll(adminPageEntries);
        }
    }

    private List<AdminPageEntry> pageList(String dbName, String collName) {
        return pages.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName),
                _ -> new CopyOnWriteArrayList<>());
    }

    void updatePageSizeInMemory(String dbName, String collName, long page, long bytesDelta) {
        underAdminPagesLock(dbName, collName, page, bytesDelta,
                () -> applyPageSizeDelta(dbName, collName, page, bytesDelta));
    }

    void updatePageSizeForUpdateInMemory(String dbName, String collName, long page, long bytesDelta) {
        underAdminPagesLock(dbName, collName, page, bytesDelta,
                () -> applyPageSizeDeltaKeepingCount(dbName, collName, page, bytesDelta));
    }

    private void underAdminPagesLock(String dbName, String collName, long page, long bytesDelta, Runnable apply) {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
        var reinterrupt = false;
        try {
            locks.lock(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);
        } catch (InterruptedException e) {
            reinterrupt = true;
            if (!acquireDespiteInterruption(pagesCollName)) {
                Thread.currentThread().interrupt();
                logger.error("Dropped a page size delta of " + bytesDelta + " for " + dbName + "|" + collName + " page "
                        + page + ": nothing recomputes page sizes before a restart, so first-fit"
                        + " may overfill that page past maxPageSize");
                return;
            }
        }
        try {
            apply.run();
        } finally {
            locks.release(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);
            if (reinterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean acquireDespiteInterruption(String pagesCollName) {
        final var deadline = System.currentTimeMillis() + INTERRUPTED_LOCK_BUDGET_MS;
        while (System.currentTimeMillis() < deadline) {
            if (locks.tryLockWrite(Globals.ADMIN_PAGES_DB_NAME, pagesCollName)) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private void applyPageSizeDelta(String dbName, String collName, long page, long bytesDelta) {
        final var pageEntries = pageList(dbName, collName);
        final var existing = findPage(pageEntries, page);
        if (existing != null) {
            existing.setPageSize(existing.getPageSize() + bytesDelta);
            existing.setEntryCount(existing.getEntryCount() + 1);
        } else {
            final var newEntry = new AdminPageEntry(dbName, collName, page);
            newEntry.setPageSize(bytesDelta);
            newEntry.setEntryCount(1);
            pageEntries.add(newEntry);
        }
    }

    private void applyPageSizeDeltaKeepingCount(String dbName, String collName, long page, long bytesDelta) {
        final var existing = findPage(pageList(dbName, collName), page);
        if (existing != null) {
            existing.setPageSize(existing.getPageSize() + bytesDelta);
        }
    }

    private static AdminPageEntry findPage(List<AdminPageEntry> entries, long page) {
        for (final var entry : entries) {
            if (entry.getPage() == page) {
                return entry;
            }
        }
        return null;
    }

    List<PkIndexEntry> getAdminPagePkIndexes(String dbName, String collName) {
        return pagesPkIndexes.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName), _ -> new ArrayList<>());
    }

    void removeAdminPageEntries(String dbName, String collName) {
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        pages.remove(collId);
        pagesPkIndexes.remove(collId);
    }

    long selectPageForInsert(String dbName, String collName, int entryByteSize) {
        return selectPageForInsert(dbName, collName, entryByteSize, Map.of());
    }

    long selectPageForInsert(String dbName, String collName, int entryByteSize, Map<Long, Long> pendingPageBytes) {
        final var maxPageBytes = configuration.getMaxPageSize();
        final var pageEntries = pageList(dbName, collName);
        // Without the pending pages, a bulk insert into a fresh collection scatters one entry per page.
        var bestFit = -1L;
        var maxPage = -1L;
        final var committed = new HashSet<Long>();
        for (final var entry : pageEntries) {
            final var page = entry.getPage();
            if (!pendingPageBytes.isEmpty()) {
                committed.add(page);
            }
            if (page > maxPage) {
                maxPage = page;
            }
            final var effectiveSize = entry.getPageSize() + pendingPageBytes.getOrDefault(page, 0L);
            if (effectiveSize + entryByteSize <= maxPageBytes && (bestFit < 0 || page < bestFit)) {
                bestFit = page;
            }
        }
        for (final var pending : pendingPageBytes.entrySet()) {
            final var page = pending.getKey();
            if (page > maxPage) {
                maxPage = page;
            }
            if (committed.contains(page)) {
                continue;
            }
            if (pending.getValue() + entryByteSize <= maxPageBytes && (bestFit < 0 || page < bestFit)) {
                bestFit = page;
            }
        }
        return bestFit >= 0 ? bestFit : maxPage + 1L;
    }
}
