package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.cache.CacheableResource;
import org.techhouse.cache.FieldIndexLookup;
import org.techhouse.cache.UserCache;
import org.techhouse.cache.heap.HeapLayout;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FieldIndexLookupTest {
    private static final String FIELD = "tag";
    private static final HeapLayout LAYOUT = new HeapLayout(8, 12, 4);
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static FieldIndexEntry<Object> entry(Object value) {
        return new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, value, new HashSet<>(Set.of("a")));
    }

    private FieldIndexLookup admitted(FieldIndexLookup lookup) throws InterruptedException {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            userCache.admitFieldIndexLookup(TestGlobals.DB, TestGlobals.COLL, FIELD, lookup);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
        return userCache.fieldIndexLookup(TestGlobals.DB, TestGlobals.COLL, FIELD);
    }

    @Test
    public void locateReturnsTheEntryAnIdSitsIn() {
        final var lookup = new FieldIndexLookup();
        final var red = entry("red");

        lookup.add("a", "tag|String", red);

        final var located = lookup.locate("a");
        assertSame(red, located.entry());
        assertEquals("tag|String", located.typeIdentifier());
        assertNull(located.next());
        assertNull(lookup.locate("missing"));
    }

    @Test
    public void addingTheSameEntryTwiceKeepsOnePlacement() {
        final var lookup = new FieldIndexLookup();
        final var red = entry("red");
        final var blue = entry("blue");

        lookup.add("a", "tag|String", red);
        lookup.add("a", "tag|String", red);
        lookup.add("a", "tag|String", blue);

        final var located = lookup.locate("a");
        assertSame(blue, located.entry());
        assertSame(red, located.next().entry());
        assertNull(located.next().next());
        assertEquals(1, lookup.size());
    }

    @Test
    public void movedReplacesAndRemoves() {
        final var lookup = new FieldIndexLookup();
        final var red = entry("red");
        lookup.add("a", "tag|String", red);
        final var now = new FieldIndexLookup.Located("tag|Number", entry(1.0), null);

        lookup.moved("a", now);
        assertSame(now, lookup.locate("a"));

        lookup.moved("a", null);
        assertNull(lookup.locate("a"));
        assertEquals(0, lookup.size());
    }

    @Test
    public void theLookupIsAdmittedOnlyUnderTheCollectionLock() throws Exception {
        final var lookup = new FieldIndexLookup();

        userCache.admitFieldIndexLookup(TestGlobals.DB, TestGlobals.COLL, FIELD, lookup);
        assertNull(userCache.fieldIndexLookup(TestGlobals.DB, TestGlobals.COLL, FIELD));

        assertSame(lookup, admitted(lookup));
    }

    @Test
    public void anAdmittedLookupIsListedAsAFieldIndexResourceAndEvictedThroughIt() throws Exception {
        final var lookup = new FieldIndexLookup();
        lookup.add("a", "tag|String", entry("red"));
        admitted(lookup);

        final var listed = userCache.listCacheableResources().stream()
                .filter(resource -> Cache.getFieldLookupIdentifier(FIELD).equals(resource.indexKey())).toList();
        assertEquals(1, listed.size());
        assertEquals(AccessKind.FIELD_INDEX, listed.getFirst().kind());
        assertEquals(CacheSizeEstimator.estimateFieldIndexLookupSize(1), listed.getFirst().estimatedSizeBytes());

        userCache.evictFieldIndex(TestGlobals.DB, TestGlobals.COLL, listed.getFirst().indexKey());
        assertNull(userCache.fieldIndexLookup(TestGlobals.DB, TestGlobals.COLL, FIELD));
    }

    @Test
    public void evictingTheDatabaseEvictsItsLookups() throws Exception {
        admitted(new FieldIndexLookup());

        userCache.evictDatabase(TestGlobals.DB);

        assertNull(userCache.fieldIndexLookup(TestGlobals.DB, TestGlobals.COLL, FIELD));
    }

    @Test
    public void theLookupIdentifierNamesItsFieldAndNoIndexType() {
        final var identifier = Cache.getFieldLookupIdentifier(FIELD);

        assertTrue(Cache.isFieldLookupIdentifier(identifier));
        assertEquals(FIELD, CacheableResource.indexedFieldOf(identifier));
        assertFalse(Cache.isFieldLookupIdentifier(Cache.getIndexIdentifier(FIELD, String.class)));
        assertFalse(Cache.isFieldLookupIdentifier(Cache.getHashIndexIdentifier(FIELD, "Object")));
    }

    @Test
    public void theLookupEstimateGrowsPerIdFromAFixedBase() {
        final var empty = CacheSizeEstimator.estimateFieldIndexLookupSize(0, LAYOUT);
        final var one = CacheSizeEstimator.estimateFieldIndexLookupSize(1, LAYOUT);
        final var thousand = CacheSizeEstimator.estimateFieldIndexLookupSize(1000, LAYOUT);

        assertTrue(empty > 0);
        assertTrue(one > empty);
        assertTrue(thousand - empty >= 1000 * (one - empty - LAYOUT.hashTableSize(1)));
    }
}
