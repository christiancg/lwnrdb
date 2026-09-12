package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

final class AdminPageCache {
    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Map<String, List<AdminPageEntry>> pages = new ConcurrentHashMap<>();
    private final Map<String, List<PkIndexEntry>> pagesPkIndexes = new ConcurrentHashMap<>();

    void loadAdminPagesForCollection(String dbName, String collName) throws IOException {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        final var pkIdx = fs.readWholePkIndexFile(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);
        pagesPkIndexes.put(pagesCollectionKey(pagesCollName), new ArrayList<>(pkIdx));
        final var pageEntries = new ArrayList<AdminPageEntry>();
        try (var pagesStream = fs.streamPages(Globals.ADMIN_PAGES_DB_NAME, pagesCollName)) {
            pagesStream.forEach(map -> map.values().stream()
                    .map(e -> AdminPageEntry.fromJsonObject(dbName, collName, e.getData())).forEach(pageEntries::add));
        }
        pages.put(collId, pageEntries);
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
        pages.put(pagesCollectionKey(collName), entries);
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
        return entries.stream().filter(p -> p.getPage() == page).findFirst().orElse(null);
    }

    void putAdminPageEntries(String dbName, String collName, List<AdminPageEntry> adminPageEntries) {
        pages.put(Cache.getCollectionIdentifier(dbName, collName), adminPageEntries);
    }

    void addAdminPageEntries(String dbName, String collName, AdminPageEntry adminPageEntry) {
        pages.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName), _ -> new ArrayList<>())
                .add(adminPageEntry);
    }

    void updatePageSizeInMemory(String dbName, String collName, long page, long bytesDelta) {
        final var pageEntries = pages.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName),
                _ -> new ArrayList<>());
        final var existing = pageEntries.stream().filter(p -> p.getPage() == page).findFirst();
        if (existing.isPresent()) {
            existing.get().setPageSize(existing.get().getPageSize() + bytesDelta);
            existing.get().setEntryCount(existing.get().getEntryCount() + 1);
        } else {
            final var newEntry = new AdminPageEntry(dbName, collName, page);
            newEntry.setPageSize(bytesDelta);
            newEntry.setEntryCount(1);
            pageEntries.add(newEntry);
        }
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
        final var pageEntries = pages.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName),
                _ -> new ArrayList<>());
        // Without the pending pages, a bulk insert into a fresh collection scatters one entry per page.
        final var committedSizeByPage = pageEntries.stream()
                .collect(Collectors.toMap(AdminPageEntry::getPage, AdminPageEntry::getPageSize));
        final var candidatePages = new TreeSet<>(committedSizeByPage.keySet());
        candidatePages.addAll(pendingPageBytes.keySet());
        for (final long page : candidatePages) {
            final var effectiveSize = committedSizeByPage.getOrDefault(page, 0L)
                    + pendingPageBytes.getOrDefault(page, 0L);
            if (effectiveSize + entryByteSize <= maxPageBytes) {
                return page;
            }
        }
        final var maxKnownPage = pageEntries.stream().mapToLong(AdminPageEntry::getPage).max().orElse(-1L);
        final var maxPendingPage = pendingPageBytes.keySet().stream().mapToLong(Long::longValue).max().orElse(-1L);
        return Math.max(maxKnownPage, maxPendingPage) + 1L;
    }
}
