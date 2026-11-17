package org.techhouse.cache;

import java.util.List;
import org.techhouse.data.admin.AdminPageEntry;

final class PageSkip {
    record Plan(List<AdminPageEntry> pages, long remainder) {
    }

    private PageSkip() {
    }

    static boolean countsMatch(List<AdminPageEntry> sortedPages, long liveRows) {
        return liveRows == totalOf(sortedPages);
    }

    static Plan plan(List<AdminPageEntry> sortedPages, long skip) {
        var remaining = skip;
        var first = 0;
        while (first < sortedPages.size()) {
            final var live = sortedPages.get(first).getEntryCount();
            if (live < 0) {
                return null;
            }
            if (remaining < live) {
                break;
            }
            remaining -= live;
            first++;
        }
        return new Plan(sortedPages.subList(first, sortedPages.size()), remaining);
    }

    private static long totalOf(List<AdminPageEntry> sortedPages) {
        var total = 0L;
        for (final var page : sortedPages) {
            total += page.getEntryCount();
        }
        return total;
    }
}
