package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.UserCache;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.test.TestUtils;

public class UserCacheFootprintTest {
    private static final String DB = "footprintDb";
    private static final String COLL = "footprintColl";

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static DbEntry entry(String id, String payload) {
        final var data = new JsonObject();
        data.addProperty("payload", payload);
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(DB);
        dbEntry.setCollectionName(COLL);
        dbEntry.set_id(id);
        dbEntry.setData(data);
        return dbEntry;
    }

    private static long trackedCollectionBytes(UserCache cache) {
        var total = 0L;
        for (final var resource : cache.listCacheableResources()) {
            if (resource.kind() == AccessKind.COLLECTION && DB.equals(resource.dbName())) {
                total += resource.estimatedSizeBytes();
            }
        }
        return total;
    }

    private static long recomputedCollectionBytes(UserCache cache) {
        final var cached = cache.getCachedCollection(DB, COLL);
        if (cached == null) {
            return 0L;
        }
        var total = 0L;
        for (final var dbEntry : cached.values()) {
            total += dbEntry.byteSize();
        }
        return total;
    }

    private static void assertTrackedMatchesRecomputed(UserCache cache) {
        assertEquals(recomputedCollectionBytes(cache), trackedCollectionBytes(cache),
                "tracked footprint must equal a full recomputation");
    }

    @Test
    public void test_tracked_footprint_matches_after_inserts() {
        final var cache = new UserCache();
        for (var i = 0; i < 25; i++) {
            cache.addEntryToCache(DB, COLL, entry("id" + i, "payload-" + i));
        }
        assertTrue(trackedCollectionBytes(cache) > 0);
        assertTrackedMatchesRecomputed(cache);
    }

    @Test
    public void test_tracked_footprint_matches_after_replacing_an_entry_with_a_bigger_one() {
        final var cache = new UserCache();
        cache.addEntryToCache(DB, COLL, entry("id1", "small"));
        final var before = trackedCollectionBytes(cache);

        cache.addEntryToCache(DB, COLL, entry("id1", "a considerably longer payload than before"));

        assertTrue(trackedCollectionBytes(cache) > before);
        assertTrackedMatchesRecomputed(cache);
    }

    @Test
    public void test_tracked_footprint_matches_after_replacing_an_entry_with_a_smaller_one() {
        final var cache = new UserCache();
        cache.addEntryToCache(DB, COLL, entry("id1", "a considerably longer payload than before"));
        final var before = trackedCollectionBytes(cache);

        cache.addEntryToCache(DB, COLL, entry("id1", "small"));

        assertTrue(trackedCollectionBytes(cache) < before);
        assertTrackedMatchesRecomputed(cache);
    }

    @Test
    public void test_tracked_footprint_matches_after_bulk_insert() {
        final var cache = new UserCache();
        final var entries = new ArrayList<DbEntry>();
        for (var i = 0; i < 30; i++) {
            entries.add(entry("bulk" + i, "payload-" + i));
        }
        cache.addEntriesToCache(DB, COLL, entries);
        assertTrackedMatchesRecomputed(cache);
    }

    @Test
    public void test_tracked_footprint_matches_after_evicting_one_entry() {
        final var cache = new UserCache();
        for (var i = 0; i < 10; i++) {
            cache.addEntryToCache(DB, COLL, entry("id" + i, "payload-" + i));
        }
        final var before = trackedCollectionBytes(cache);

        cache.evictEntry(DB, COLL, "id3");

        assertTrue(trackedCollectionBytes(cache) < before);
        assertTrackedMatchesRecomputed(cache);
    }

    @Test
    public void test_tracked_footprint_is_zero_after_evicting_the_collection() {
        final var cache = new UserCache();
        for (var i = 0; i < 10; i++) {
            cache.addEntryToCache(DB, COLL, entry("id" + i, "payload-" + i));
        }
        cache.evictCollection(DB, COLL);

        assertEquals(0L, trackedCollectionBytes(cache));
        assertTrackedMatchesRecomputed(cache);
    }

    @Test
    public void test_tracked_footprint_matches_under_mixed_traffic() {
        final var cache = new UserCache();
        for (var round = 0; round < 8; round++) {
            for (var i = 0; i < 12; i++) {
                cache.addEntryToCache(DB, COLL, entry("id" + i, "payload-" + round + "-" + "x".repeat(round)));
            }
            if (round % 3 == 0) {
                cache.evictEntry(DB, COLL, "id" + round);
            }
            assertTrackedMatchesRecomputed(cache);
        }
    }
}
