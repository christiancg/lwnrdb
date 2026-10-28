package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheableResource;
import org.techhouse.cache.FieldIndexLookup;
import org.techhouse.cache.Measure;
import org.techhouse.cache.UserCache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class UserCacheRunningTotalTest {
    private static final String DB = "runningDb";
    private static final String FIELD = "status";
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory
            .getThreadMXBean();

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

    private static DbEntry document(String collName, String id) {
        final var data = new JsonObject();
        data.addProperty("_id", id);
        data.addProperty("payload", "value-" + id);
        return DbEntry.fromJsonObject(DB, collName, data);
    }

    private static List<PkIndexEntry> pkRows(String collName, int count) {
        final var rows = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < count; i++) {
            rows.add(new PkIndexEntry(DB, collName, "row-" + String.format("%04d", i), i * 10L, 10, 0));
        }
        return rows;
    }

    private static List<FieldIndexEntry<?>> eightFirstFieldRows() {
        final List<FieldIndexEntry<?>> rows = new ArrayList<>();
        for (var i = 0; i < 8; i++) {
            rows.add(new FieldIndexEntry<>(DB, "first", "value-" + i, new HashSet<>(Set.of("id-" + i))));
        }
        return rows;
    }

    private static long listedBytes(UserCache cache) {
        return cache.listCacheableResources(Measure.REMEMBERED).stream()
                .mapToLong(CacheableResource::estimatedSizeBytes).sum();
    }

    private void underLock(String collName, ThrowingRunnable body) throws Exception {
        locks.lock(DB, collName);
        try {
            body.run();
        } finally {
            locks.release(DB, collName);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    public void test_running_total_matches_listing_after_mixed_tiers() throws Exception {
        final var cache = new UserCache();
        final var fs = mock(FileSystem.class);
        when(fs.readWholePkIndexFile(DB, "first")).thenReturn(pkRows("first", 30));
        when(fs.readWholePkIndexFile(DB, "second")).thenReturn(pkRows("second", 12));
        when(fs.pkIndexFileBytes(anyString(), anyString())).thenReturn(400L);
        TestUtils.setPrivateField(cache, "fs", fs);

        for (var i = 0; i < 20; i++) {
            cache.addEntryToCache(DB, "first", document("first", "d" + i));
            cache.addEntryToCache(DB, "second", document("second", "d" + i));
        }
        cache.evictEntry(DB, "first", "d3");
        final var indexKey = Cache.getIndexIdentifier(FIELD, String.class);
        underLock("first", () -> {
            cache.getPkIndexAndLoadIfNecessary(DB, "first").add(new PkIndexEntry(DB, "first", "zz", 0, 1, 0));
            cache.admitMaintainedFieldIndex(DB, "first", indexKey, eightFirstFieldRows());
            final var lookup = new FieldIndexLookup();
            lookup.add("id-1", indexKey, null);
            cache.admitFieldIndexLookup(DB, "first", FIELD, lookup);
        });
        underLock("second", () -> cache.getPkIndexAndLoadIfNecessary(DB, "second").removeFirst());
        cache.rememberFieldIndexBatch(DB, "first", indexKey, 8, 96L, 2);
        assertEquals(listedBytes(cache), cache.footprintBytes(), "every tier is charged exactly once");

        cache.evictCollectionDocuments(DB, "second");
        cache.evictFieldIndex(DB, "first", Cache.getFieldLookupIdentifier(FIELD));
        assertEquals(listedBytes(cache), cache.footprintBytes(), "evictions retire what they remove");

        cache.evictCollection(DB, "first");
        assertEquals(listedBytes(cache), cache.footprintBytes());
        cache.evictDatabase(DB);
        assertEquals(0L, cache.footprintBytes());
        assertTrue(cache.listCacheableResources().isEmpty());
    }

    @Test
    public void test_footprint_reads_no_resources() {
        final var cache = new UserCache();
        for (var collection = 0; collection < 1000; collection++) {
            cache.addEntryToCache(DB, "coll" + collection, document("coll" + collection, "only"));
        }
        for (var warm = 0; warm < 10_000; warm++) {
            cache.footprintBytes();
        }
        final var thread = Thread.currentThread().threadId();
        final var before = THREADS.getThreadAllocatedBytes(thread);
        var total = 0L;
        for (var call = 0; call < 10_000; call++) {
            total += cache.footprintBytes();
        }
        final var allocated = THREADS.getThreadAllocatedBytes(thread) - before;

        assertTrue(total > 0L);
        assertTrue(allocated / 10_000 < 256L, "reading the footprint allocated " + allocated + " bytes for 10k calls"
                + " over 1000 resident collections; a walk of them costs kilobytes per call");
    }
}
