package org.techhouse.ops.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import org.techhouse.data.FieldIndexEntry;

final class SortedIdWalk {
    private SortedIdWalk() {
    }

    private static final class Cursor {
        private final List<FieldIndexEntry<?>> entries;
        private final boolean backward;
        private int taken;

        private Cursor(List<FieldIndexEntry<?>> entries, boolean backward) {
            this.entries = entries;
            this.backward = backward;
        }

        private boolean hasCurrent() {
            return taken < entries.size();
        }

        private FieldIndexEntry<?> current() {
            return entries.get(backward ? entries.size() - 1 - taken : taken);
        }
    }

    static List<String> idsInOrder(List<List<FieldIndexEntry<?>>> typeLists, Comparator<FieldIndexEntry<?>> order,
            long maxIds) {
        final var cursors = new ArrayList<Cursor>(typeLists.size());
        for (final var entries : typeLists) {
            if (entries.getFirst().getValue() instanceof String) {
                cursors.add(new Cursor(smallestFirst(entries, order, maxIds), false));
            } else {
                cursors.add(new Cursor(entries,
                        entries.size() > 1 && order.compare(entries.getFirst(), entries.getLast()) > 0));
            }
        }
        final var ordered = new ArrayList<String>();
        final var run = new ArrayList<FieldIndexEntry<?>>();
        while (ordered.size() < maxIds) {
            final var smallest = smallestCurrent(cursors, order);
            if (smallest == null) {
                break;
            }
            run.clear();
            for (final var cursor : cursors) {
                while (cursor.hasCurrent() && order.compare(cursor.current(), smallest) == 0) {
                    run.add(cursor.current());
                    cursor.taken++;
                    if (cursor.hasCurrent() && order.compare(run.getLast(), cursor.current()) > 0) {
                        return null;
                    }
                }
            }
            ordered.addAll(mergedIds(run));
        }
        return ordered;
    }

    private static FieldIndexEntry<?> smallestCurrent(List<Cursor> cursors, Comparator<FieldIndexEntry<?>> order) {
        FieldIndexEntry<?> smallest = null;
        for (final var cursor : cursors) {
            if (cursor.hasCurrent() && (smallest == null || order.compare(cursor.current(), smallest) < 0)) {
                smallest = cursor.current();
            }
        }
        return smallest;
    }

    private static List<FieldIndexEntry<?>> smallestFirst(List<FieldIndexEntry<?>> entries,
            Comparator<FieldIndexEntry<?>> order, long maxIds) {
        final var kept = new PriorityQueue<>(order.reversed());
        var keptIds = 0L;
        for (final var entry : entries) {
            kept.add(entry);
            keptIds += entry.getIds().size();
            for (var head = kept.peek(); head != null && keptIds - head.getIds().size() >= maxIds; head = kept.peek()) {
                kept.poll();
                keptIds -= head.getIds().size();
            }
        }
        final var smallest = new ArrayList<>(kept);
        smallest.sort(order);
        return smallest;
    }

    private static List<String> mergedIds(List<FieldIndexEntry<?>> run) {
        if (run.size() == 1) {
            return IndexEntryReader.sortedIds(run.getFirst());
        }
        final var ids = new ArrayList<String>();
        for (final var entry : run) {
            ids.addAll(entry.getIds());
        }
        Collections.sort(ids);
        return ids;
    }
}
