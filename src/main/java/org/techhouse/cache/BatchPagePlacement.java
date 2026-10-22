package org.techhouse.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongUnaryOperator;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.fs.PagePlacement;

final class BatchPagePlacement {
    private static final long NO_PAGE = -1L;

    private static final class Pending {
        private long bytes;
    }

    private static final class Slot {
        private final long page;
        private final long liveBytes;
        private final long fileLength;
        private final Pending pending;
        private long smallestRejected = Long.MAX_VALUE;

        private Slot(long page, long liveBytes, long fileLength, Pending pending) {
            this.page = page;
            this.liveBytes = liveBytes;
            this.fileLength = fileLength;
            this.pending = pending;
        }

        private boolean accepts(long size, long maxPageBytes) {
            if (size >= smallestRejected) {
                return false;
            }
            final var accepted = PagePlacement.acceptsInsert(liveBytes + pending.bytes, fileLength + pending.bytes,
                    size, maxPageBytes);
            if (!accepted) {
                smallestRejected = size;
            }
            return accepted;
        }

        private boolean rejectsEverySizeFrom(long smallestSize) {
            return smallestRejected <= smallestSize;
        }
    }

    private final long maxPageBytes;
    private final long smallestEntry;
    private final List<Slot> open = new ArrayList<>();
    private final Map<Long, Pending> pendingByPage = new HashMap<>();
    private final TreeMap<Long, Pending> freshPages = new TreeMap<>();
    private long maxPage = NO_PAGE;

    BatchPagePlacement(List<AdminPageEntry> pageEntries, LongUnaryOperator fileLengthOf, long maxPageBytes,
            int[] entryByteSizes) {
        this.maxPageBytes = maxPageBytes;
        this.smallestEntry = Arrays.stream(entryByteSizes).min().orElse(0);
        final var ordered = new ArrayList<>(pageEntries);
        ordered.sort(Comparator.comparingLong(AdminPageEntry::getPage));
        for (final var entry : ordered) {
            final var page = entry.getPage();
            maxPage = Math.max(maxPage, page);
            final var pending = pendingByPage.computeIfAbsent(page, _ -> new Pending());
            open.add(new Slot(page, entry.getPageSize(), fileLengthOf.applyAsLong(page), pending));
        }
    }

    long place(int size) {
        final var existing = firstOpenAccepting(size);
        final var fresh = lowestFreshPageWithRoom(size);
        var target = existing == null ? NO_PAGE : existing.page;
        if (fresh != NO_PAGE && (target == NO_PAGE || fresh < target)) {
            target = fresh;
        }
        if (target == NO_PAGE) {
            target = maxPage + 1L;
        }
        pendingByPage.computeIfAbsent(target, this::freshPendingOf).bytes += size;
        maxPage = Math.max(maxPage, target);
        return target;
    }

    private Pending freshPendingOf(long page) {
        final var pending = new Pending();
        freshPages.put(page, pending);
        return pending;
    }

    private Slot firstOpenAccepting(long size) {
        var index = 0;
        while (index < open.size()) {
            final var slot = open.get(index);
            if (slot.accepts(size, maxPageBytes)) {
                return slot;
            }
            if (slot.rejectsEverySizeFrom(smallestEntry)) {
                open.remove(index);
            } else {
                index++;
            }
        }
        return null;
    }

    private long lowestFreshPageWithRoom(long size) {
        for (final var fresh : freshPages.entrySet()) {
            if (fresh.getValue().bytes + size <= maxPageBytes) {
                return fresh.getKey();
            }
        }
        return NO_PAGE;
    }
}
