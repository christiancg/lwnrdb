package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.cache.Measure;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FieldIndexSizeDeltaTest {
    private static final String FIELD = "size";
    private static final int LARGE_ENTRY_IDS = 50_000;
    private final Cache cache = IocContainer.get(Cache.class);
    private FieldIndexBatchSupport support;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        support = new FieldIndexBatchSupport();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private Map<String, Long> remembered() {
        final var bytes = new HashMap<String, Long>();
        for (final var resource : cache.userCache().listCacheableResources(Measure.REMEMBERED)) {
            if (resource.kind() == AccessKind.FIELD_INDEX && TestGlobals.COLL.equals(resource.collName())) {
                bytes.put(resource.indexKey(), resource.estimatedSizeBytes());
            }
        }
        return bytes;
    }

    private Map<String, List<FieldIndexEntry<?>>> residentLists() {
        final var lists = new HashMap<String, List<FieldIndexEntry<?>>>();
        for (final var type : IndexKind.scalarIndexClasses()) {
            putIfResident(lists, Cache.getIndexIdentifier(FIELD, type), support.resident(FIELD, type));
        }
        for (final var kind : IndexKind.HASH_KINDS) {
            putIfResident(lists, Cache.getHashIndexIdentifier(FIELD, kind.label()), support.resident(FIELD, kind));
        }
        return lists;
    }

    private static void putIfResident(Map<String, List<FieldIndexEntry<?>>> lists, String identifier,
            List<FieldIndexEntry<?>> list) {
        if (list != null) {
            lists.put(identifier, list);
        }
    }

    @Test
    public void test_the_remembered_size_equals_a_full_measure_after_adds_removes_moves_and_empties() throws Exception {
        final var random = new Random(91);
        var checked = 0;
        for (var batch = 0; batch < 300; batch++) {
            if (batch % 20 == 0) {
                support.admitEveryType(FIELD);
            }
            final var before = residentLists();
            final var rememberedBefore = remembered();
            final var measuredBefore = new HashMap<String, Long>();
            before.forEach((identifier, list) -> measuredBefore.put(identifier,
                    CacheSizeEstimator.estimateFieldIndexSize(new ArrayList<>(list))));
            final var ids = FieldIndexBatchSupport.randomIds(random, 1 + random.nextInt(12), 40);
            ids.forEach(id -> support.mutate(id, random));

            support.applyBatch(FIELD, ids);

            final var after = residentLists();
            final var rememberedAfter = remembered();
            for (final var resident : before.entrySet()) {
                final var identifier = resident.getKey();
                final var list = after.get(identifier);
                if (list != resident.getValue() || list.isEmpty()) {
                    continue;
                }
                final var delta = CacheSizeEstimator.estimateFieldIndexSize(list) - measuredBefore.get(identifier);
                final var count = list.size();
                final var expected = count * (Math.max(0L, rememberedBefore.get(identifier) + delta) / count);
                assertEquals(expected, rememberedAfter.get(identifier), identifier + " after batch " + batch);
                checked++;
            }
        }
        assertTrue(checked > 100, "the batches must have run against resident lists, checked " + checked);
    }

    @Test
    public void test_a_batch_on_a_large_entry_does_not_measure_its_other_ids() throws Exception {
        final var ids = new ArrayList<String>(LARGE_ENTRY_IDS);
        for (var i = 0; i < LARGE_ENTRY_IDS; i++) {
            ids.add("big" + i);
            support.put("big" + i, new JsonString("shared"));
        }
        support.applyBatch(FIELD, ids);
        support.admitEveryType(FIELD);
        support.put("extra", new JsonString("shared"));

        try (var estimator = mockStatic(CacheSizeEstimator.class, CALLS_REAL_METHODS)) {
            support.applyBatch(FIELD, List.of("extra"));

            estimator.verify(() -> CacheSizeEstimator.estimateFieldIndexSize(anyList()), never());
            estimator.verify(() -> CacheSizeEstimator.fieldIndexIdSize(anyString()), times(1));
        }
        final var resident = support.resident(FIELD, String.class);
        assertEquals(LARGE_ENTRY_IDS + 1, resident.getFirst().getIds().size());
        final var rememberedBytes = remembered().get(Cache.getIndexIdentifier(FIELD, String.class));
        assertEquals(CacheSizeEstimator.estimateFieldIndexSize(resident), rememberedBytes,
                "one entry, so the per-entry division is exact");
    }
}
