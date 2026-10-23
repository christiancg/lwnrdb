package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.fs.PagePlacement;

public class BatchPagePlacementTest {
    private static final String DB = "placementDb";
    private static final String COLL = "placementColl";
    private static final long MEGABYTE = 1024L * 1024L;
    private static final int SCENARIOS = 3_000;

    private static AdminPageEntry page(long number, long liveBytes) {
        final var entry = new AdminPageEntry(DB, COLL, number);
        entry.setPageSize(liveBytes);
        entry.setEntryCount(1);
        return entry;
    }

    private static long[] viaBatch(List<AdminPageEntry> pages, LongUnaryOperator lengths, long max, int[] sizes) {
        final var placement = new BatchPagePlacement(pages, lengths, max, sizes);
        final var targets = new long[sizes.length];
        for (var index = 0; index < sizes.length; index++) {
            targets[index] = placement.place(sizes[index]);
        }
        return targets;
    }

    private static long[] oldRescan(List<AdminPageEntry> pages, LongUnaryOperator lengths, long max, int[] sizes) {
        final var pendingPageBytes = new HashMap<Long, Long>();
        final var targets = new long[sizes.length];
        for (var index = 0; index < sizes.length; index++) {
            targets[index] = oldSelect(pages, sizes[index], pendingPageBytes, lengths, max);
            pendingPageBytes.merge(targets[index], (long) sizes[index], Long::sum);
        }
        return targets;
    }

    private static long oldSelect(List<AdminPageEntry> pages, int size, Map<Long, Long> pendingPageBytes,
            LongUnaryOperator lengths, long max) {
        var bestFit = -1L;
        var maxPage = -1L;
        for (final var entry : pages) {
            final var number = entry.getPage();
            if (number > maxPage) {
                maxPage = number;
            }
            if (bestFit >= 0 && number >= bestFit) {
                continue;
            }
            final var pending = pendingPageBytes.getOrDefault(number, 0L);
            if (PagePlacement.acceptsInsert(entry.getPageSize() + pending, lengths.applyAsLong(number) + pending, size,
                    max)) {
                bestFit = number;
            }
        }
        for (final var pending : pendingPageBytes.entrySet()) {
            final var number = pending.getKey();
            if (number > maxPage) {
                maxPage = number;
            }
            if (pages.stream().anyMatch(entry -> entry.getPage() == number)) {
                continue;
            }
            if (pending.getValue() + size <= max && (bestFit < 0 || number < bestFit)) {
                bestFit = number;
            }
        }
        return bestFit >= 0 ? bestFit : maxPage + 1L;
    }

    @Test
    public void an_empty_collection_packs_page_zero_then_opens_page_one() {
        final var half = (int) (MEGABYTE / 2);
        final var sizes = new int[]{half, half, half, half, half};

        assertArrayEquals(new long[]{0, 0, 1, 1, 2}, viaBatch(List.of(), _ -> 0L, MEGABYTE, sizes));
    }

    @Test
    public void the_lowest_page_with_room_wins_whatever_order_the_pages_were_added() {
        final var pages = new ArrayList<>(List.of(page(3, 0), page(0, MEGABYTE - 10), page(1, 0)));

        assertArrayEquals(new long[]{1}, viaBatch(pages, _ -> 0L, MEGABYTE, new int[]{100}));
    }

    @Test
    public void a_page_full_of_dead_bytes_is_reused_once_a_quarter_of_it_is_dead() {
        final var max = MEGABYTE;
        final var pages = List.of(page(0, max / 2), page(1, 10));

        assertArrayEquals(new long[]{0}, viaBatch(pages, _ -> max, max, new int[]{1_000}));
        assertArrayEquals(new long[]{1},
                viaBatch(List.of(page(0, max - 10), page(1, 10)), _ -> max, max, new int[]{1_000}));
    }

    @Test
    public void a_page_that_only_fits_the_small_entries_still_takes_them_later_in_the_batch() {
        final var max = 1_000L;
        final var pages = List.of(page(0, 900), page(1, 0));

        assertArrayEquals(new long[]{1, 0, 1, 0}, viaBatch(pages, _ -> 0L, max, new int[]{500, 90, 500, 10}));
    }

    @Test
    public void the_file_length_of_each_page_is_read_once_not_once_per_entry() {
        final var pages = new ArrayList<AdminPageEntry>();
        for (var number = 0; number < 100; number++) {
            pages.add(page(number, MEGABYTE));
        }
        final var reads = new AtomicInteger();
        final var sizes = new int[2_000];
        java.util.Arrays.fill(sizes, 700);

        viaBatch(pages, _ -> {
            reads.incrementAndGet();
            return MEGABYTE;
        }, MEGABYTE, sizes);

        assertEquals(100, reads.get());
    }

    @Test
    public void it_places_every_entry_exactly_as_rescanning_every_page_per_entry_did() {
        final var random = new Random(20261021L);
        for (var scenario = 0; scenario < SCENARIOS; scenario++) {
            final var max = new long[]{1_000L, 4_096L, MEGABYTE}[random.nextInt(3)];
            final var pages = new ArrayList<AdminPageEntry>();
            final var lengths = new HashMap<Long, Long>();
            final var count = random.nextInt(60);
            for (var index = 0; index < count; index++) {
                final var number = (long) index * (1 + random.nextInt(2));
                final var live = random.nextInt(10) < 7
                        ? max - random.nextInt((int) Math.min(max, 400))
                        : (long) (random.nextDouble() * max);
                pages.add(page(number, Math.clamp(live, 0L, max)));
                lengths.put(number, Math.min(max * 2, live + (long) (random.nextDouble() * max / 2)));
            }
            Collections.shuffle(pages, random);
            final var sizes = new int[random.nextInt(300)];
            final var largest = Math.max(1, (int) (max / (1 + random.nextInt(6))));
            for (var index = 0; index < sizes.length; index++) {
                sizes[index] = 1 + random.nextInt(largest);
            }
            final LongUnaryOperator lengthOf = number -> lengths.getOrDefault(number, 0L);

            assertArrayEquals(oldRescan(pages, lengthOf, max, sizes), viaBatch(pages, lengthOf, max, sizes),
                    "scenario " + scenario + " max=" + max + " pages=" + count + " entries=" + sizes.length);
        }
    }
}
