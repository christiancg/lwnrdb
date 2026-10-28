package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.PkIndexList;
import org.techhouse.data.PkIndexEntry;

public class PkIndexEditsRemoveTest {
    private static String idOf(int index) {
        return String.format("id-%05d", index);
    }

    private static List<PkIndexEntry> rows(int count) {
        return new ArrayList<>(IntStream.range(0, count)
                .mapToObj(index -> new PkIndexEntry("db", "coll", idOf(index), index, 1, 0)).toList());
    }

    private static List<String> idsWithParity(int count, int parity) {
        return IntStream.range(0, count).filter(index -> index % 2 == parity).mapToObj(PkIndexEditsRemoveTest::idOf)
                .toList();
    }

    private static List<String> idsOf(List<PkIndexEntry> rows) {
        return rows.stream().map(PkIndexEntry::getValue).toList();
    }

    @Test
    public void test_a_chunked_list_removes_by_point_edits_and_ignores_absent_ids() {
        final var chunked = PkIndexList.of(rows(3000));
        final var removed = new ArrayList<>(idsWithParity(3000, 0));
        removed.add("absent");

        PkIndexEdits.removeAll(chunked, removed);

        assertEquals(idsWithParity(3000, 1), idsOf(chunked));
    }

    @Test
    public void test_a_plain_list_removes_a_few_ids_by_point_edits() {
        final var plain = rows(10);

        PkIndexEdits.removeAll(plain, List.of(idOf(2), idOf(7), "absent"));

        assertEquals(List.of(idOf(0), idOf(1), idOf(3), idOf(4), idOf(5), idOf(6), idOf(8), idOf(9)), idsOf(plain));
    }

    @Test
    public void test_a_plain_list_removes_many_ids_in_one_pass() {
        final var plain = rows(500);

        PkIndexEdits.removeAll(plain, idsWithParity(500, 0));

        assertEquals(idsWithParity(500, 1), idsOf(plain));
    }
}
