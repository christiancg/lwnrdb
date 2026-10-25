package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.PkIndexList;
import org.techhouse.data.PkIndexEntry;

public class PkIndexListTest {
    private static final String DB = "pkListDb";
    private static final String COLL = "pkListColl";

    private static PkIndexEntry row(String id) {
        return new PkIndexEntry(DB, COLL, id, id.length(), 1, 0);
    }

    private static List<PkIndexEntry> sortedRows(int count) {
        final var rows = new ArrayList<PkIndexEntry>(count);
        for (var i = 0; i < count; i++) {
            rows.add(row(String.format("id-%06d", i)));
        }
        return rows;
    }

    private static void insertSorted(List<PkIndexEntry> rows, PkIndexEntry row) {
        final var at = Collections.binarySearch(rows, row.getValue());
        if (at < 0) {
            rows.add(-at - 1, row);
        } else {
            rows.set(at, row);
        }
    }

    private static void removeSorted(List<PkIndexEntry> rows, String id) {
        final var at = Collections.binarySearch(rows, id);
        if (at >= 0) {
            rows.remove(at);
        }
    }

    @Test
    public void test_insert_sorted_orders_rows_by_id_and_keeps_search_exact() {
        final var random = new Random(7);
        final var oracle = new ArrayList<>(sortedRows(3_000));
        final var list = PkIndexList.of(oracle);
        for (var batch = 0; batch < 20; batch++) {
            final var fresh = new ArrayList<PkIndexEntry>();
            for (var i = 0; i < 1 + random.nextInt(900); i++) {
                fresh.add(row(String.format("id-%06d-%03d", random.nextInt(3_000), batch)));
            }
            fresh.sort(java.util.Comparator.comparing(PkIndexEntry::getValue));
            final var unique = fresh.stream().distinct().toList();
            oracle.addAll(unique);
            oracle.sort(java.util.Comparator.comparing(PkIndexEntry::getValue));

            list.insertSorted(unique);

            assertEquals(oracle, list);
            assertSameAnswer(oracle, list, unique.getFirst().getValue());
            assertSameAnswer(oracle, list, unique.getLast().getValue() + "0");
            insertSorted(list, row("id-x" + batch));
            insertSorted(oracle, row("id-x" + batch));
            removeSorted(list, unique.getFirst().getValue());
            removeSorted(oracle, unique.getFirst().getValue());
        }
        assertEquals(oracle, list);
    }

    private static void assertSameAnswer(List<PkIndexEntry> oracle, PkIndexList list, String id) {
        assertEquals(Collections.binarySearch(oracle, id), list.search(id), "search " + id);
        assertEquals(Collections.binarySearch(oracle, id), PkIndexList.positionOf(list, id), "positionOf " + id);
    }

    @Test
    public void test_search_answers_exactly_like_binary_search_on_a_sorted_list() {
        final var random = new Random(31L);
        for (final var count : new int[]{0, 1, 2, 511, 512, 513, 1_024, 1_025, 5_000, 20_000}) {
            final var oracle = new ArrayList<PkIndexEntry>();
            for (var i = 0; i < count; i++) {
                oracle.add(row(String.format("id-%06d", i * 2)));
            }
            final var list = PkIndexList.of(oracle);
            for (var probe = -2; probe <= count * 2 + 2; probe += 1 + random.nextInt(37)) {
                assertSameAnswer(oracle, list, String.format("id-%06d", Math.max(0, probe)));
            }
            assertSameAnswer(oracle, list, "");
            assertSameAnswer(oracle, list, "zzz");
            assertSameAnswer(oracle, list, "id-");
            for (var i = 0; i < oracle.size(); i += 53) {
                assertSameAnswer(oracle, list, oracle.get(i).getValue());
            }
        }
    }

    @Test
    public void test_search_agrees_at_every_chunk_boundary() {
        final var oracle = new ArrayList<>(sortedRows(5_000));
        final var list = PkIndexList.of(oracle);
        for (var boundary = 0; boundary < oracle.size(); boundary += 512) {
            for (var offset = -2; offset <= 2; offset++) {
                final var at = Math.clamp(boundary + offset, 0, oracle.size() - 1);
                assertSameAnswer(oracle, list, oracle.get(at).getValue());
                assertSameAnswer(oracle, list, oracle.get(at).getValue() + "0");
                assertSameAnswer(oracle, list, oracle.get(at).getValue().substring(0, 8));
            }
        }
    }

    @Test
    public void test_search_stays_exact_while_the_list_is_edited() {
        final var random = new Random(77L);
        final var oracle = new ArrayList<>(sortedRows(2_000));
        final var list = PkIndexList.of(oracle);
        for (var operation = 0; operation < 30_000; operation++) {
            final var id = String.format("id-%06d", random.nextInt(8_000));
            if (random.nextBoolean()) {
                insertSorted(oracle, row(id));
                insertSorted(list, row(id));
            } else {
                removeSorted(oracle, id);
                removeSorted(list, id);
            }
            assertSameAnswer(oracle, list, String.format("id-%06d", random.nextInt(8_000)));
        }
    }

    @Test
    public void test_position_of_falls_back_to_binary_search_for_any_other_list() {
        final var plain = new ArrayList<>(sortedRows(100));

        assertEquals(Collections.binarySearch(plain, "id-000042"), PkIndexList.positionOf(plain, "id-000042"));
        assertEquals(Collections.binarySearch(plain, "missing"), PkIndexList.positionOf(plain, "missing"));
        assertEquals(-1, PkIndexList.positionOf(new ArrayList<>(), "anything"));
    }
}
