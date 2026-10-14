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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
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
        doThrow(new IOException("disk full")).when(spied).rewriteIndexType(anyString(), anyString(), anyString(),
                anyString(), any());
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
    public void aRebuiltLookupAfterEvictionMatchesTheFiles() throws Exception {
        seedTwoBatches();
        cache.evictFieldIndex(TestGlobals.DB, TestGlobals.COLL, Cache.getIndexIdentifier(FIELD, String.class));
        support.put("c", JsonBoolean.of(true));
        support.put("a", new JsonNumber(3));

        support.applyBatchHoldingTheCollection(FIELD, List.of("c", "a"));

        assertNotNull(lookup());
        assertEquals(Map.of("Boolean", Set.of("true => c"), "Number", Set.of("3 => a,b")),
                FieldIndexBatchSupport.snapshot(FIELD));
        assertNull(support.residentMismatch(FIELD));
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
