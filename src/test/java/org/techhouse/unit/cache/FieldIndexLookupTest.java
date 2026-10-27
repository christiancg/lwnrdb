package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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

    private static FieldIndexLookup.Located placed(FieldIndexLookup lookup, String id) {
        final var located = lookup.locate(id);
        assertNotNull(located);
        return located;
    }

    @Test
    public void locateReturnsTheEntryAnIdSitsIn() {
        final var lookup = new FieldIndexLookup();
        final var red = entry("red");

        lookup.add("a", "tag|String", red);

        final var located = placed(lookup, "a");
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

        final var located = placed(lookup, "a");
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
        assertEquals(now, lookup.locate("a"));

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
    public void theLookupEstimateIsItsTableAndGrowsWithIt() {
        final var empty = CacheSizeEstimator.estimateFieldIndexLookupSize(0, LAYOUT);
        final var twelve = CacheSizeEstimator.estimateFieldIndexLookupSize(12, LAYOUT);
        final var thirteen = CacheSizeEstimator.estimateFieldIndexLookupSize(13, LAYOUT);
        final var million = CacheSizeEstimator.estimateFieldIndexLookupSize(1_000_000, LAYOUT);

        assertEquals(FieldIndexLookup.estimatedBytes(13, LAYOUT), thirteen);
        assertEquals(empty, twelve);
        assertEquals(2 * (LAYOUT.referenceArraySize(32) - LAYOUT.referenceArraySize(16)) + LAYOUT.arraySize(1, 32)
                - LAYOUT.arraySize(1, 16), thirteen - twelve);
        assertTrue(million < 1_000_000L * 20, "a lookup costs under 20 bytes per id, got " + million);
    }

    @Test
    public void collidingIdsAreBothLocatedAndSurviveTheOtherRemoval() {
        final var lookup = new FieldIndexLookup();
        final var red = entry("red");
        final var blue = entry("blue");
        assertEquals("Aa".hashCode(), "BB".hashCode());

        lookup.add("Aa", "tag|String", red);
        lookup.add("BB", "tag|Number", blue);

        assertSame(red, placed(lookup, "Aa").entry());
        assertSame(blue, placed(lookup, "BB").entry());
        assertEquals("tag|Number", placed(lookup, "BB").typeIdentifier());
        lookup.moved("Aa", null);
        assertNull(lookup.locate("Aa"));
        assertSame(blue, placed(lookup, "BB").entry());
        assertEquals(1, lookup.size());
    }

    @Test
    public void theTableGrowsAndShrinksKeepingEveryPlacement() {
        final var lookup = new FieldIndexLookup();
        final var entries = new ArrayList<FieldIndexEntry<Object>>();
        for (var i = 0; i < 5000; i++) {
            entries.add(entry("v" + i));
            lookup.add("id" + i, i % 2 == 0 ? "tag|String" : "tag|Number", entries.get(i));
        }
        assertEquals(5000, lookup.size());
        for (var i = 0; i < 5000; i += 3) {
            lookup.moved("id" + i, null);
        }
        for (var i = 0; i < 5000; i++) {
            final var located = lookup.locate("id" + i);
            if (i % 3 == 0) {
                assertNull(located);
            } else {
                assertNotNull(located);
                assertSame(entries.get(i), located.entry());
                assertEquals(i % 2 == 0 ? "tag|String" : "tag|Number", located.typeIdentifier());
            }
        }
        for (var i = 0; i < 5000; i++) {
            lookup.moved("id" + i, null);
        }
        assertEquals(0, lookup.size());
        assertNull(lookup.locate("id1"));
    }

    @Test
    public void anIdPlacedTwiceMovesBackToOnePlacement() {
        final var lookup = new FieldIndexLookup(4);
        final var red = entry("red");
        final var one = entry(1.0);
        lookup.add("a", "tag|String", red);
        lookup.add("a", "tag|Number", one);
        lookup.add("a", "tag|Number", one);

        assertEquals(1, lookup.size());
        final var located = placed(lookup, "a");
        assertSame(one, located.entry());
        assertSame(red, located.next().entry());

        lookup.moved("a", new FieldIndexLookup.Located("tag|String", red, null));
        assertSame(red, placed(lookup, "a").entry());
        assertNull(placed(lookup, "a").next());
        assertEquals(1, lookup.size());

        lookup.moved("b", new FieldIndexLookup.Located("tag|String", red, located));
        assertSame(located, placed(lookup, "b").next());
        assertEquals(2, lookup.size());
        lookup.moved("b", null);
        assertEquals(1, lookup.size());
    }
}
