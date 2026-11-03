package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class AdmissionEvictionTest {
    private static final String DB = "evictDb";
    private static final String FIELD = "status";
    private static final String KEY = Cache.getIndexIdentifier(FIELD, String.class);

    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration configuration = Configuration.getInstance();
    private Long savedMaxMemory;
    private UserCache cache;
    private UsageTracker usage;
    private final AtomicLong clock = new AtomicLong(1_000L);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        cache = new UserCache();
        usage = spy(new UsageTracker());
        TestUtils.setPrivateField(cache, "usageTracker", usage);
        TestUtils.setPrivateField(cache, "admission", new AdmissionEviction(cache, clock::get));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void maxMemory(long bytes) throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", bytes);
    }

    private static DbEntry document(String collName, String id) {
        final var data = new JsonObject();
        data.addProperty("_id", id);
        data.addProperty("payload", "x".repeat(64));
        return DbEntry.fromJsonObject(DB, collName, data);
    }

    private long residentDocuments(String collName, int count) {
        for (var i = 0; i < count; i++) {
            CacheResidents.residentDocument(cache, Cache.getCollectionIdentifier(DB, collName),
                    document(collName, "d" + i));
        }
        return CacheResidents.footprint(cache, AccessKind.COLLECTION);
    }

    private static List<FieldIndexEntry<?>> fieldRows(String collName, int count) {
        final List<FieldIndexEntry<?>> rows = new ArrayList<>();
        for (var i = 0; i < count; i++) {
            rows.add(new FieldIndexEntry<>(DB, collName, "value-" + i, new HashSet<>(Set.of("id-" + i))));
        }
        return rows;
    }

    private static List<PkIndexEntry> pkRows(String collName, int count) {
        final var rows = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < count; i++) {
            rows.add(new PkIndexEntry(DB, collName, "row-" + String.format("%04d", i), i * 10L, 10, 0));
        }
        return rows;
    }

    private boolean holdsDocuments(String collName) {
        return cache.getCachedCollection(DB, collName) != null;
    }

    private void read(String collName, Runnable body) throws InterruptedException {
        locks.lockRead(DB, collName);
        try {
            body.run();
        } finally {
            locks.releaseRead(DB, collName);
        }
    }

    @Test
    public void aFieldIndexAdmissionUnderTheReadLockEvictsThatCollectionsDocuments() throws Exception {
        final var documents = residentDocuments("orders", 20);
        final var rows = fieldRows("orders", 10);
        final var listBytes = CacheSizeEstimator.estimateFieldIndexSize(rows);
        maxMemory(documents + listBytes - 1);

        read("orders", () -> cache.admitMaintainedFieldIndex(DB, "orders", KEY, rows));

        assertNotNull(cache.residentFieldIndex(DB, "orders", KEY), "the background worker's list is admitted");
        assertFalse(holdsDocuments("orders"), "its own collection's query-admitted documents made room");
        assertEquals(listBytes, cache.footprintBytes());
    }

    @Test
    public void aPkAdmissionEvictsInRankOrderAndNeverAnotherPk() throws Exception {
        final var other = CacheResidents.residentPk(cache, Cache.getCollectionIdentifier(DB, "other"),
                pkRows("other", 5));
        residentDocuments("cold", 10);
        final var both = residentDocuments("hot", 10);
        for (var access = 0; access < 5; access++) {
            usage.recordAccess(AccessKind.COLLECTION, DB, "hot", null);
        }
        final var target = pkRows("target", 8);
        final var targetBytes = CacheSizeEstimator.pkIndexBytesPerEntry(target) * target.size();
        maxMemory(cache.footprintBytes() - both / 2 + targetBytes);

        assertTrue(cache.shouldCache(DB, new AdmissionRequest(AccessKind.PK_INDEX, DB, "target", null, targetBytes)));

        assertFalse(holdsDocuments("cold"), "the least used documents go first");
        assertTrue(holdsDocuments("hot"));
        assertTrue(cache.pkIndexes().containsValue(other), "a pk admission never evicts another pk index");
    }

    @Test
    public void aDocumentAdmissionNeverEvicts() throws Exception {
        residentDocuments("orders", 10);
        maxMemory(cache.footprintBytes());

        cache.addEntryToCache(DB, "fresh", document("fresh", "new"));

        assertFalse(holdsDocuments("fresh"));
        assertTrue(holdsDocuments("orders"));
        verify(usage, never()).accessCountFor(any());
    }

    @Test
    public void aCollectionWriteLockedByAnotherThreadIsSkipped() throws Exception {
        residentDocuments("busy", 10);
        final var both = residentDocuments("free", 10);
        for (var access = 0; access < 5; access++) {
            usage.recordAccess(AccessKind.COLLECTION, DB, "free", null);
        }
        final var rows = fieldRows("target", 4);
        final var listBytes = CacheSizeEstimator.estimateFieldIndexSize(rows);
        maxMemory(both - both / 2 + listBytes);
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var writer = Thread.ofPlatform().start(() -> {
            try {
                locks.lock(DB, "busy");
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.release(DB, "busy");
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            read("target", () -> cache.admitMaintainedFieldIndex(DB, "target", KEY, rows));
        } finally {
            release.countDown();
            writer.join(5_000L);
        }

        assertTrue(holdsDocuments("busy"), "a writer's collection is never touched");
        assertFalse(holdsDocuments("free"));
        assertNotNull(cache.residentFieldIndex(DB, "target", KEY));
    }

    @Test
    public void aFieldWhoseLockThisThreadHoldsIsSkipped() throws Exception {
        CacheResidents.residentFieldIndex(cache, Cache.getCollectionIdentifier(DB, "indexed"), KEY,
                fieldRows("indexed", 10));
        final var target = pkRows("target", 4);
        final var targetBytes = CacheSizeEstimator.pkIndexBytesPerEntry(target) * target.size();
        maxMemory(targetBytes + 1);
        locks.lockIndexRead(DB, "indexed", FIELD);
        try {
            assertFalse(
                    cache.shouldCache(DB, new AdmissionRequest(AccessKind.PK_INDEX, DB, "target", null, targetBytes)));
        } finally {
            locks.releaseIndexRead(DB, "indexed", FIELD);
        }

        assertNotNull(cache.residentFieldIndex(DB, "indexed", KEY), "a list this thread may be reading stays");
    }

    @Test
    public void anInfeasibleRequestNeverLists() throws Exception {
        residentDocuments("orders", 10);
        maxMemory(cache.footprintBytes() * 2);

        assertFalse(cache.shouldCache(DB,
                new AdmissionRequest(AccessKind.PK_INDEX, DB, "target", null, cache.footprintBytes() * 3)));

        assertTrue(holdsDocuments("orders"));
        verify(usage, never()).accessCountFor(any());
    }

    @Test
    public void anAttemptThatFindsEveryCandidateBusyBacksOff() throws Exception {
        final var documents = residentDocuments("busy", 10);
        maxMemory(documents);
        final var request = new AdmissionRequest(AccessKind.FIELD_INDEX, DB, "target", FIELD, documents / 2);
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var writer = Thread.ofPlatform().start(() -> {
            try {
                locks.lock(DB, "busy");
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.release(DB, "busy");
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            assertFalse(cache.shouldCache(DB, request));
            verify(usage, atLeastOnce()).accessCountFor(any());
            clearInvocations(usage);

            assertFalse(cache.shouldCache(DB, request));
            assertFalse(cache.wouldAdmit(request), "a tier backing off does not promise room");
            verify(usage, never()).accessCountFor(any());

            clock.addAndGet(AdmissionEviction.BACKOFF_NANOS);
            assertTrue(cache.wouldAdmit(request));
            assertFalse(cache.shouldCache(DB, request));
            verify(usage, atLeastOnce()).accessCountFor(any());
        } finally {
            release.countDown();
            writer.join(5_000L);
        }
        assertTrue(cache.shouldCache(DB, new AdmissionRequest(AccessKind.FIELD_INDEX, DB, "target", FIELD, 0L)));
    }

    private static final String HOT_KEY = Cache.getIndexIdentifier("rank", String.class);

    private void accesses(AccessKind kind, String collName, String field, int times) {
        for (var access = 0; access < times; access++) {
            usage.recordAccess(kind, DB, collName, field);
        }
    }

    private long coldIndex(String collName) {
        CacheResidents.residentFieldIndex(cache, Cache.getCollectionIdentifier(DB, collName),
                Cache.getIndexIdentifier(FIELD, String.class), fieldRows(collName, 20));
        return cache.footprintBytes();
    }

    private boolean admitHot(List<FieldIndexEntry<?>> rows) throws InterruptedException {
        read("hot", () -> cache.admitMaintainedFieldIndex(DB, "hot", HOT_KEY, rows));
        return cache.residentFieldIndex(DB, "hot", HOT_KEY) != null;
    }

    private void nextQuery() {
        clock.addAndGet(RefusalHistory.MIN_SPACING_NANOS);
    }

    private boolean coldResident(String collName) {
        return cache.residentFieldIndex(DB, collName, Cache.getIndexIdentifier(FIELD, String.class)) != null;
    }

    @Test
    public void hotFieldIndexEvictsColderFieldIndexPastRatio() throws Exception {
        final var cold = coldIndex("cold");
        final var hot = fieldRows("hot", 20);
        maxMemory(cold + CacheSizeEstimator.estimateFieldIndexSize(hot) - 1);
        accesses(AccessKind.FIELD_INDEX, "hot", "rank", 8);
        accesses(AccessKind.FIELD_INDEX, "cold", FIELD, 2);

        assertFalse(admitHot(hot));
        nextQuery();
        assertFalse(admitHot(hot));
        nextQuery();
        assertTrue(admitHot(hot), "a requester refused twice and used 4x more displaces the colder index");

        assertFalse(coldResident("cold"));
        assertTrue(cache.footprintBytes() <= cold + CacheSizeEstimator.estimateFieldIndexSize(hot) - 1);
    }

    @Test
    public void equalUsageNeverEvictsSameTier() throws Exception {
        final var cold = coldIndex("cold");
        final var hot = fieldRows("hot", 20);
        maxMemory(cold + CacheSizeEstimator.estimateFieldIndexSize(hot) - 1);
        accesses(AccessKind.FIELD_INDEX, "hot", "rank", 5);
        accesses(AccessKind.FIELD_INDEX, "cold", FIELD, 5);

        for (var attempt = 0; attempt < 6; attempt++) {
            clock.addAndGet(AdmissionEviction.BACKOFF_NANOS);
            assertFalse(admitHot(hot), "below the ratio the two indexes never displace each other");
        }
        assertTrue(coldResident("cold"));
    }

    @Test
    public void requesterRefusedOnceDoesNotEvict() throws Exception {
        final var cold = coldIndex("cold");
        final var hot = fieldRows("hot", 20);
        maxMemory(cold + CacheSizeEstimator.estimateFieldIndexSize(hot) - 1);
        accesses(AccessKind.FIELD_INDEX, "hot", "rank", 100);

        assertFalse(admitHot(hot));
        nextQuery();
        assertFalse(admitHot(hot), "one earlier refusal is not enough");
        assertTrue(coldResident("cold"));
        clock.addAndGet(RefusalHistory.WINDOW_NANOS + 1);
        assertFalse(admitHot(hot), "refusals older than a minute do not count");
        nextQuery();
        assertFalse(admitHot(hot));
        assertTrue(coldResident("cold"));
        verify(usage, never()).lastAccessFor(any());

        nextQuery();
        assertTrue(admitHot(hot));
        assertFalse(coldResident("cold"));
    }

    @Test
    public void pkEvictedForHotFieldIndexIsMarkedRefusedAndPointScanned() throws Exception {
        final var fs = mock(FileSystem.class);
        final var rows = pkRows("other", 40);
        when(fs.trustedLiveRows(DB, "other")).thenReturn(OptionalLong.of(rows.size()));
        when(fs.readWholePkIndexFile(DB, "other")).thenReturn(rows);
        when(fs.pkRowsFor(anyString(), anyString(), anyCollection())).thenReturn(Map.of("row-0001", rows.get(1)));
        TestUtils.setPrivateField(cache, "fs", fs);
        final var otherId = Cache.getCollectionIdentifier(DB, "other");
        CacheResidents.residentPk(cache, otherId, rows);
        final var pkBytes = cache.footprintBytes();
        final var hot = fieldRows("hot", 20);
        maxMemory(pkBytes + CacheSizeEstimator.estimateFieldIndexSize(hot) - 1);
        accesses(AccessKind.FIELD_INDEX, "hot", "rank", 8);
        accesses(AccessKind.PK_INDEX, "other", null, 1);

        assertFalse(admitHot(hot));
        nextQuery();
        assertFalse(admitHot(hot));
        nextQuery();
        assertTrue(admitHot(hot));

        assertFalse(cache.pkIndexes().containsKey(otherId));
        assertTrue(cache.sizes().refusedPk(otherId).displaced());
        locks.lockRead(DB, "other");
        try {
            assertEquals("row-0001", cache.pkRowFor(DB, "other", "row-0001").getValue());
            assertFalse(cache.isResidentPkIndex(DB, "other", cache.getPkIndexAndLoadIfNecessary(DB, "other")),
                    "a displaced pk is not re-admitted by evicting the index that displaced it");
        } finally {
            locks.releaseRead(DB, "other");
        }
        verify(fs).pkRowsFor(DB, "other", List.of("row-0001"));
        assertNotNull(cache.residentFieldIndex(DB, "hot", HOT_KEY));

        cache.evictFieldIndex(DB, "hot", HOT_KEY);
        residentDocuments("filler", 30);
        read("other", () -> {
            try {
                cache.getPkIndexAndLoadIfNecessary(DB, "other");
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
        assertTrue(cache.pkIndexes().containsKey(otherId), "once only documents hold its room the pk returns");
        assertFalse(holdsDocuments("filler"));
        assertNull(cache.sizes().refusedPk(otherId));
    }

    @Test
    public void ownFieldAndLockedIndexAreNeverVictims() throws Exception {
        final var ownOtherType = Cache.getIndexIdentifier("rank", Double.class);
        CacheResidents.residentFieldIndex(cache, Cache.getCollectionIdentifier(DB, "hot"), ownOtherType,
                fieldRows("hot", 20));
        final var both = coldIndex("busy");
        final var hot = fieldRows("hot", 20);
        maxMemory(both + CacheSizeEstimator.estimateFieldIndexSize(hot) - 1);
        accesses(AccessKind.FIELD_INDEX, "hot", "rank", 1000);
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var indexer = Thread.ofPlatform().start(() -> {
            try {
                locks.lockIndex(DB, "busy", FIELD);
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.releaseIndex(DB, "busy", FIELD);
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            for (var attempt = 0; attempt < 4; attempt++) {
                clock.addAndGet(AdmissionEviction.BACKOFF_NANOS);
                assertFalse(admitHot(hot));
            }
        } finally {
            release.countDown();
            indexer.join(5_000L);
        }

        assertNotNull(cache.residentFieldIndex(DB, "hot", ownOtherType), "the requester's own field stays");
        assertTrue(coldResident("busy"), "an index another thread holds stays");
    }

    @Test
    public void aRefusedTypeServedElsewhereDisplacesAColderIndexOnlyOnceRefusedRepeatedly() throws Exception {
        final var facade = IocContainer.get(Cache.class);
        final var shared = TestUtils.getPrivateField(facade, "userCache", UserCache.class);
        final var cold = coldIndex("cold");
        final var hotBytes = CacheSizeEstimator.estimateFieldIndexSize(fieldRows("hot", 20));
        maxMemory(cold + hotBytes - 1);
        accesses(AccessKind.FIELD_INDEX, "hot", "rank", 8);
        TestUtils.setPrivateField(facade, "userCache", cache);
        try {
            assertFalse(facade.displacesColderIndexes(DB, "hot", HOT_KEY), "a type never refused has nothing to do");
            cache.sizes().refuseFieldIndex(Cache.getCollectionIdentifier(DB, "hot"), HOT_KEY, hotBytes);
            assertFalse(facade.displacesColderIndexes(DB, "hot", HOT_KEY));
            assertFalse(facade.displacesColderIndexes(DB, "hot", HOT_KEY), "one query reading twice is one refusal");
            nextQuery();
            assertFalse(facade.displacesColderIndexes(DB, "hot", HOT_KEY));
            assertTrue(coldResident("cold"));
            nextQuery();
            assertTrue(facade.displacesColderIndexes(DB, "hot", HOT_KEY));
            assertFalse(coldResident("cold"), "the third refusal made room for the hot type's load");
        } finally {
            TestUtils.setPrivateField(facade, "userCache", shared);
        }
    }
}
