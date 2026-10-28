package org.techhouse.cache;

import java.util.Arrays;
import java.util.List;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;

final class PageSkip {
    record Plan(List<AdminPageEntry> pages, long remainder) {
    }

    private PageSkip() {
    }

    static Plan plan(List<AdminPageEntry> sortedPages, List<PkIndexEntry> pk, long skip) {
        final var pageNumbers = new long[sortedPages.size()];
        for (var i = 0; i < pageNumbers.length; i++) {
            pageNumbers[i] = sortedPages.get(i).getPage();
        }
        final var live = new int[pageNumbers.length];
        for (final var row : pk) {
            final var at = Arrays.binarySearch(pageNumbers, row.getPage());
            if (at >= 0) {
                live[at]++;
            }
        }
        var remaining = skip;
        var first = 0;
        while (first < live.length && remaining >= live[first]) {
            remaining -= live[first];
            first++;
        }
        return new Plan(sortedPages.subList(first, sortedPages.size()), remaining);
    }
}
