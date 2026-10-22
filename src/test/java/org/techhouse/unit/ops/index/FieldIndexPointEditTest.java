package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class FieldIndexPointEditTest {
    private static final String FIELD = "price";
    private static final int DOCUMENTS = 10_000;
    private static final int POINT_EDIT_LIMIT = 64;
    private static final Map<String, Integer> SCANS = new ConcurrentHashMap<>();
    private final Cache cache = IocContainer.get(Cache.class);
    private FieldIndexBatchSupport support;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        SCANS.clear();
        support = new FieldIndexBatchSupport();
        final var ids = IntStream.range(0, DOCUMENTS).mapToObj(FieldIndexPointEditTest::idOf).toList();
        ids.forEach(id -> support.put(id, new JsonNumber(valueOf(id))));
        support.applyBatchHoldingTheCollection(FIELD, ids);
        support.admitEveryType(FIELD);
        support.put(idOf(0), new JsonNumber(-1));
        support.applyBatchHoldingTheCollection(FIELD, List.of(idOf(0)));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static String idOf(int i) {
        return String.format("d%05d", i);
    }

    private static int valueOf(String id) {
        return Integer.parseInt(id.substring(1));
    }

    private ScanCountingIndex countingResidentIndex() throws Exception {
        final var mapType = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var resident = TestUtils.getPrivateField(cache.userCache(), "fieldIndexMap", mapType)
                .get(Cache.getCollectionIdentifier(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL));
        final var identifier = Cache.getIndexIdentifier(FIELD, Number.class);
        final var counting = new ScanCountingIndex(resident.get(identifier));
        resident.put(identifier, counting);
        return counting;
    }

    private List<FieldIndexEntry<?>> residentIndex() {
        return support.resident(FIELD, Number.class);
    }

    private static boolean holds(List<FieldIndexEntry<?>> index, double value, String id) {
        return index.stream()
                .anyMatch(entry -> ((Number) entry.getValue()).doubleValue() == value && entry.getIds().contains(id));
    }

    @Test
    public void test_single_save_scans_nothing() throws Exception {
        final var counting = countingResidentIndex();
        support.put(idOf(7), new JsonNumber(DOCUMENTS + 0.5));

        support.applyBatchHoldingTheCollection(FIELD, List.of(idOf(7)));

        assertEquals(Map.of(), SCANS, "a one-id batch must edit the resident index in place");
        assertSame(counting, residentIndex());
        assertNull(support.residentMismatch(FIELD));
    }

    @Test
    public void test_single_delete_scans_nothing() throws Exception {
        countingResidentIndex();
        support.delete(idOf(9));

        support.applyBatchHoldingTheCollection(FIELD, List.of(idOf(9)));

        assertEquals(Map.of(), SCANS);
        assertNull(support.residentMismatch(FIELD));
    }

    @Test
    public void test_emptied_entry_is_removed() throws Exception {
        support.put(idOf(11), new JsonNumber(DOCUMENTS + 1));

        support.applyBatchHoldingTheCollection(FIELD, List.of(idOf(11)));

        assertFalse(residentIndex().stream().anyMatch(entry -> ((Number) entry.getValue()).doubleValue() == 11),
                "the value 11 lost its only id");
        assertNull(support.residentMismatch(FIELD));
    }

    @Test
    public void test_emptied_then_refilled_entry_stays() throws Exception {
        support.put(idOf(12), new JsonNumber(DOCUMENTS + 2));
        support.put(idOf(13), new JsonNumber(12));

        support.applyBatchHoldingTheCollection(FIELD, List.of(idOf(12), idOf(13)));

        assertTrue(holds(residentIndex(), 12, idOf(13)));
        assertNull(support.residentMismatch(FIELD));
    }

    @Test
    public void test_new_value_lands_sorted() throws Exception {
        support.put(idOf(14), new JsonNumber(14.5));

        support.applyBatchHoldingTheCollection(FIELD, List.of(idOf(14)));

        assertTrue(holds(residentIndex(), 14.5, idOf(14)));
        assertNull(support.residentMismatch(FIELD), "the new entry must sit at its sorted position");
    }

    @Test
    public void test_large_batch_merges_by_binary_search_and_keeps_the_list_object() throws Exception {
        final var counting = countingResidentIndex();
        final var ids = IntStream.range(100, 100 + POINT_EDIT_LIMIT * 2).mapToObj(FieldIndexPointEditTest::idOf)
                .toList();
        ids.forEach(id -> support.put(id, new JsonNumber(valueOf(id) + 0.25)));

        support.applyBatchHoldingTheCollection(FIELD, ids);

        assertSame(counting, residentIndex());
        assertEquals(Map.of(), SCANS, "the merge finds its positions by binary search and scans nothing");
        assertNull(support.residentMismatch(FIELD));
    }

    private static final class ScanCountingIndex extends ArrayList<FieldIndexEntry<?>> {
        ScanCountingIndex(Collection<FieldIndexEntry<?>> entries) {
            super(entries);
        }

        @Override
        public void sort(Comparator<? super FieldIndexEntry<?>> comparator) {
            SCANS.merge("sort", 1, Integer::sum);
            super.sort(comparator);
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public Stream<FieldIndexEntry<?>> stream() {
            SCANS.merge("stream", 1, Integer::sum);
            return super.stream();
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public Iterator<FieldIndexEntry<?>> iterator() {
            SCANS.merge("iterator", 1, Integer::sum);
            return super.iterator();
        }

        @Override
        public void forEach(Consumer<? super FieldIndexEntry<?>> action) {
            SCANS.merge("forEach", 1, Integer::sum);
            super.forEach(action);
        }

        @Override
        public boolean remove(Object entry) {
            SCANS.merge("remove(Object)", 1, Integer::sum);
            return super.remove(entry);
        }

        @Override
        public boolean removeIf(Predicate<? super FieldIndexEntry<?>> filter) {
            SCANS.merge("removeIf", 1, Integer::sum);
            return super.removeIf(filter);
        }
    }
}
