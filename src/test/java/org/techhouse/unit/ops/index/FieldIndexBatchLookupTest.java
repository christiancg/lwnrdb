package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.FieldIndexLookup;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.index.FieldIndexBatch;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FieldIndexBatchLookupTest {
    private static final String FIELD = "tag";
    private static final String SLOT_CLASS = "org.techhouse.ops.index.FieldIndexSlot";
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem realFs = IocContainer.get(FileSystem.class);
    private FieldIndexBatchSupport support;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        support = new FieldIndexBatchSupport();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", realFs);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private Object lookup() {
        return cache.fieldIndexLookup(TestGlobals.DB, TestGlobals.COLL, FIELD);
    }

    private void seedTwoBatches() throws Exception {
        support.put("a", new JsonString("x"));
        support.put("b", new JsonNumber(3));
        support.applyBatchHoldingTheCollection(FIELD, List.of("a", "b"));
        support.put("a", new JsonString("y"));
        support.applyBatchHoldingTheCollection(FIELD, List.of("a"));
    }

    @Test
    public void aSuccessfulBatchCachesItsLookupOnceEveryTypeIsResident() throws Exception {
        support.put("a", new JsonString("x"));

        support.applyBatchHoldingTheCollection(FIELD, List.of("a"));

        assertNotNull(lookup());
    }

    @Test
    public void aSecondBatchReusesTheResidentLookup() throws Exception {
        seedTwoBatches();
        final var first = lookup();
        final var resident = support.resident(FIELD, String.class);
        support.put("b", new JsonString("z"));

        support.applyBatchHoldingTheCollection(FIELD, List.of("b"));

        assertSame(first, lookup(), "the batch mutated the cached lookup instead of rebuilding it");
        assertSame(resident, support.resident(FIELD, String.class));
        assertEquals(Map.of("String", Set.of("y => a", "z => b")), FieldIndexBatchSupport.snapshot(FIELD));
        assertNull(support.residentMismatch(FIELD));
    }

    @Test
    public void aFailedFlushEvictsTheLookupWithItsLists() throws Exception {
        seedTwoBatches();
        assertNotNull(lookup());
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", spied);
        doThrow(new IOException("disk full")).when(spied).appendIndexLog(anyString(), anyString(), anyString(),
                anyString(), any(), any());
        final var object = new JsonObject();
        object.addProperty("_id", "a");
        object.addProperty(FIELD, "w");
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL,
                DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object));
        registerIndex();

        assertThrows(IOException.class,
                () -> IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of("a")));

        assertNull(lookup());
        assertNull(support.resident(FIELD, String.class));
    }

    private void registerIndex() {
        final var entry = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        final var indexes = new HashSet<>(entry.getIndexes());
        indexes.add(FIELD);
        entry.setIndexes(indexes);
    }

    @Test
    public void evictingOneTypeListEvictsTheLookup() throws Exception {
        seedTwoBatches();
        assertNotNull(lookup());

        cache.evictFieldIndex(TestGlobals.DB, TestGlobals.COLL, Cache.getIndexIdentifier(FIELD, Number.class));

        assertNull(lookup());
        assertNotNull(support.resident(FIELD, String.class));
    }

    @Test
    public void evictingTheLookupResourceKeepsTheLists() throws Exception {
        seedTwoBatches();

        cache.evictFieldIndex(TestGlobals.DB, TestGlobals.COLL, Cache.getFieldLookupIdentifier(FIELD));

        assertNull(lookup());
        assertNotNull(support.resident(FIELD, String.class));
        assertNotNull(support.resident(FIELD, Number.class));
    }

    @Test
    public void evictingEveryTypeOrTheCollectionEvictsTheLookup() throws Exception {
        seedTwoBatches();
        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);
        assertNull(lookup());

        seedTwoBatches();
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
        assertNull(lookup());
    }

    @Test
    public void aRebuiltLookupAfterEvictionIsCachedOneBatchLaterAndMatchesTheFiles() throws Exception {
        seedTwoBatches();
        cache.evictFieldIndex(TestGlobals.DB, TestGlobals.COLL, Cache.getIndexIdentifier(FIELD, String.class));
        support.put("c", JsonBoolean.of(true));
        support.put("a", new JsonNumber(3));

        support.applyBatchHoldingTheCollection(FIELD, List.of("c", "a"));
        assertNull(lookup(), "a batch that read a type from disk holds only its own ids, never cached");
        support.applyBatchHoldingTheCollection(FIELD, List.of("c"));

        assertNotNull(lookup());
        assertEquals(Map.of("Boolean", Set.of("true => c"), "Number", Set.of("3 => a,b")),
                FieldIndexBatchSupport.snapshot(FIELD));
        assertNull(support.residentMismatch(FIELD));
        assertNull(support.lookupMismatch(FIELD, 3));
    }

    private FieldIndexLookup openBatchLookup(List<String> ids) throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        locks.lockIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        try {
            final var batch = FieldIndexBatch.open(TestGlobals.DB, TestGlobals.COLL, FIELD, ids);
            for (final var id : ids) {
                final var doc = support.documentOf(id, FIELD);
                batch.apply(id, doc == null ? null : doc.getData().get(FIELD));
            }
            final var built = TestUtils.getPrivateField(batch, "lookup", FieldIndexLookup.class);
            batch.flush();
            return built;
        } finally {
            locks.releaseIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    private void seedManyIds() throws Exception {
        final var ids = new ArrayList<String>();
        for (var i = 0; i < 200; i++) {
            ids.add("id" + i);
            support.put("id" + i, i % 2 == 0 ? new JsonString("v" + (i % 7)) : new JsonNumber(i % 5));
        }
        support.applyBatchHoldingTheCollection(FIELD, ids);
        support.applyBatchHoldingTheCollection(FIELD, List.of("id0"));
    }

    @Test
    public void test_a_batch_over_a_non_resident_type_resolves_only_its_ids() throws Exception {
        seedManyIds();
        assertNotNull(lookup());
        cache.evictFieldIndex(TestGlobals.DB, TestGlobals.COLL, Cache.getIndexIdentifier(FIELD, Number.class));
        support.put("id1", new JsonString("moved"));
        support.put("id2", new JsonNumber(42));

        final var built = openBatchLookup(List.of("id1", "id2", "absent"));

        assertEquals(2, built.size(), "only the batch's ids are resolved, not the 200 the field holds");
        assertNull(lookup());
        assertNull(support.lookupMismatch(FIELD, 200));
        assertEquals(List.of("String=moved"), placementsOf("id1"));
        assertEquals(List.of("Number=42"), placementsOf("id2"));
        assertNull(support.residentMismatch(FIELD));
    }

    private static List<String> placementsOf(String id) throws IOException {
        final var placements = new ArrayList<String>();
        for (final var type : FieldIndexBatchSupport.loadedTypes(FIELD).entrySet()) {
            for (final var entry : type.getValue()) {
                if (entry.getIds().contains(id)) {
                    placements.add(type.getKey() + "=" + FieldIndexEntry.fileKeyOf(entry.getValue()));
                }
            }
        }
        return placements;
    }

    @Test
    public void test_a_scoped_lookup_is_never_admitted_and_evicts_the_cached_one() throws Exception {
        seedManyIds();
        final var cached = lookup();
        assertNotNull(cached);
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        support.put("id3", new JsonString("v3"));
        TestUtils.setPrivateField(config, "maxMemoryBytes", 1L);
        try {
            cache.evictFieldIndexLookup(TestGlobals.DB, TestGlobals.COLL, FIELD);
            final var built = openBatchLookup(List.of("id3"));

            assertEquals(1, built.size(), "a lookup that cannot be admitted holds only the batch's ids");
            assertNull(lookup());
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
        assertNull(support.residentMismatch(FIELD));
    }

    @Test
    public void test_the_next_batch_after_admission_builds_the_full_lookup() throws Exception {
        seedManyIds();
        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);
        support.put("id5", new JsonString("v5"));

        final var scoped = openBatchLookup(List.of("id5"));
        assertEquals(1, scoped.size());
        assertNull(lookup());
        assertNotNull(support.resident(FIELD, String.class), "the batch admitted what it read from disk");

        final var full = openBatchLookup(List.of("id7"));
        assertEquals(200, full.size());
        assertSame(full, lookup());
        assertNull(support.lookupMismatch(FIELD, 200));
    }

    @Test
    public void theLookupIsNotAdmittedWhileCachingIsDisabled() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", -1L);
        try {
            seedTwoBatches();

            assertNull(lookup());
            assertNull(support.resident(FIELD, String.class));
            assertEquals(Map.of("String", Set.of("y => a"), "Number", Set.of("3 => b")),
                    FieldIndexBatchSupport.snapshot(FIELD));
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void caseFoldedEqualValuesStayDistinctBuckets() throws Exception {
        support.put("u", new JsonString("Abc"));
        support.put("l", new JsonString("abc"));
        support.applyBatchHoldingTheCollection(FIELD, List.of("u", "l"));
        support.put("u2", new JsonString("Abc"));
        support.put("l2", new JsonString("abc"));
        support.put("m", new JsonString("ABC"));

        support.applyBatchHoldingTheCollection(FIELD, List.of("u2", "l2", "m"));

        assertEquals(Map.of("String", Set.of("Abc => u,u2", "abc => l,l2", "ABC => m")),
                FieldIndexBatchSupport.snapshot(FIELD));
        assertNull(support.residentMismatch(FIELD));
    }

    @Test
    public void negativeZeroAndZeroShareABucket() throws Exception {
        support.put("p", new JsonNumber(0.0));
        support.applyBatchHoldingTheCollection(FIELD, List.of("p"));
        support.put("n", new JsonNumber(-0.0));

        support.applyBatchHoldingTheCollection(FIELD, List.of("n"));

        assertEquals(Map.of("Number", Set.of("0 => n,p")), FieldIndexBatchSupport.snapshot(FIELD));
    }
}
