package org.techhouse.ops.admin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.bckg_ops.events.EntityRef;
import org.techhouse.cache.Cache;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ioc.IocContainer;

final class WritePathPageBooking {
    private static final Cache cache = IocContainer.get(Cache.class);

    private final String dbName;
    private final String collName;
    private final Set<Long> existing = new LinkedHashSet<>();
    private final Map<Long, AdminPageEntry> missing = new LinkedHashMap<>();

    private WritePathPageBooking(String dbName, String collName) {
        this.dbName = dbName;
        this.collName = collName;
    }

    static void book(String dbName, String collName, List<EntityRef> created, List<EntityRef> others,
            long incarnation) {
        final var booking = new WritePathPageBooking(dbName, collName);
        booking.created(created);
        booking.others(others);
        cache.addAdminPageEntries(dbName, collName, new ArrayList<>(booking.missing.values()));
        PageRowWriteBack.touched(dbName, collName, incarnation, booking.touchedPages(), System.currentTimeMillis());
    }

    private void created(List<EntityRef> refs) {
        final var seen = HashSet.<String>newHashSet(refs.size());
        for (final var ref : refs) {
            if (!seen.add(ref.id()) || isKnownRow(ref.page())) {
                continue;
            }
            final var row = missing.computeIfAbsent(ref.page(), page -> new AdminPageEntry(dbName, collName, page));
            row.setEntryCount(row.getEntryCount() + 1);
            row.setPageSize(row.getPageSize() + ref.byteSize());
        }
    }

    private void others(List<EntityRef> refs) {
        for (final var ref : refs) {
            isKnownRow(ref.page());
        }
    }

    private boolean isKnownRow(long page) {
        if (existing.contains(page)) {
            return true;
        }
        if (missing.containsKey(page) || cache.getAdminPageEntry(dbName, collName, page) == null) {
            return false;
        }
        existing.add(page);
        return true;
    }

    private List<Long> touchedPages() {
        final var pages = new ArrayList<Long>(existing.size() + missing.size());
        pages.addAll(existing);
        pages.addAll(missing.keySet());
        return pages;
    }
}
