package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheResidents;
import org.techhouse.cache.CacheableResource;
import org.techhouse.cache.Measure;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.cache.UserCache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class MemoryManagementSweepMeasureTest {
    private static final long MAX_BYTES = 1000L;
    private static final String DB = "sweepDb";
    private static final String COLL = "sweepColl";
    private static final String INDEX_KEY = Cache.getIndexIdentifier("status", String.class);
    private static final int IDS = 20;

    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private final Configuration configuration = Configuration.getInstance();
    private final UserCache userCache = mock(UserCache.class);
    private UserCache realCache;
    private Long realMaxMemory;

    private static final AtomicInteger WALKS = new AtomicInteger();

    private static final class CountingList extends ArrayList<FieldIndexEntry<?>> {
        @Override
        @SuppressWarnings("NullableProblems")
        public Iterator<FieldIndexEntry<?>> iterator() {
            WALKS.incrementAndGet();
            return super.iterator();
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        realMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        realCache = TestUtils.getPrivateField(memoryManagement, "userCache", UserCache.class);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", MAX_BYTES);
        TestUtils.setPrivateField(memoryManagement, "userCache", userCache);
    }

    @AfterEach
    public void tearDown() throws Exception {
        memoryManagement.stopSweepThread();
        TestUtils.setPrivateField(memoryManagement, "userCache", realCache);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", realMaxMemory);
        TestUtils.standardTearDown();
    }

    private void rememberedBytes(long bytes) {
        when(userCache.footprintBytes()).thenReturn(bytes);
        when(userCache.listCacheableResources(Measure.REMEMBERED))
                .thenReturn(List.of(new CacheableResource(AccessKind.COLLECTION, "userDb", "coll", null, bytes)));
    }

    @Test
    public void test_a_sweep_below_the_floor_never_measures_in_full() {
        rememberedBytes(MAX_BYTES / 2 - 1);

        memoryManagement.runEvictionSweep();

        verify(userCache, atLeastOnce()).footprintBytes();
        verify(userCache, never()).listCacheableResources(Measure.REMEMBERED);
        verify(userCache, never()).listCacheableResources(Measure.FULL);
        verify(userCache, never()).listCacheableResources(Measure.FULL_FORCED);
    }

    @Test
    public void test_a_sweep_at_the_floor_measures_only_what_drifted() {
        rememberedBytes(MAX_BYTES / 2);

        memoryManagement.runEvictionSweep();

        verify(userCache).listCacheableResources(Measure.FULL);
        verify(userCache, never()).listCacheableResources(Measure.FULL_FORCED);
    }

    @Test
    public void test_a_remembered_total_over_the_cap_measures_everything() {
        rememberedBytes(MAX_BYTES * 2);

        memoryManagement.runEvictionSweep();

        verify(userCache).listCacheableResources(Measure.FULL_FORCED);
        verify(userCache, never()).listCacheableResources(Measure.FULL);
    }

    @Test
    public void test_an_empty_cache_is_not_measured_in_full() {
        memoryManagement.runEvictionSweep();

        verify(userCache, never()).listCacheableResources(Measure.FULL);
        verify(userCache, never()).listCacheableResources(Measure.FULL_FORCED);
    }

    private static void measuredIndexIn(UserCache cache) {
        final var list = new CountingList();
        for (var i = 0; i < IDS; i++) {
            list.add(new FieldIndexEntry<>(DB, COLL, "value-" + i, Set.of("id-" + i)));
        }
        CacheResidents.residentUnmeasuredFieldIndex(cache, Cache.getCollectionIdentifier(DB, COLL), INDEX_KEY, list);
        WALKS.set(0);
        cache.listCacheableResources(Measure.FULL);
        assertTrue(WALKS.getAndSet(0) > 0, "an index with no remembered measure is measured");
    }

    private static long rememberedBytesOf(UserCache cache) {
        return cache.listCacheableResources(Measure.REMEMBERED).stream()
                .mapToLong(CacheableResource::estimatedSizeBytes).sum();
    }

    @Test
    public void test_a_sweep_without_changes_measures_nothing() throws Exception {
        final var cache = new UserCache();
        measuredIndexIn(cache);
        TestUtils.setPrivateField(memoryManagement, "userCache", cache);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", rememberedBytesOf(cache));

        memoryManagement.runEvictionSweep();
        memoryManagement.runEvictionSweep();

        assertEquals(0, WALKS.get(), "an index nothing changed is charged its remembered size");
        assertNotNull(cache.residentFieldIndex(DB, COLL, INDEX_KEY));
    }

    @Test
    public void test_an_index_changed_by_a_tenth_is_measured_once_and_reset() {
        final var cache = new UserCache();
        measuredIndexIn(cache);

        cache.rememberFieldIndexBatch(DB, COLL, INDEX_KEY, IDS, 0L, IDS / 10 - 1);
        cache.listCacheableResources(Measure.FULL);
        assertEquals(0, WALKS.get(), "less than a tenth of the ids changed");

        cache.rememberFieldIndexBatch(DB, COLL, INDEX_KEY, IDS, 0L, 1);
        cache.listCacheableResources(Measure.FULL);
        assertTrue(WALKS.getAndSet(0) > 0, "a tenth of the ids changed");

        cache.listCacheableResources(Measure.FULL);
        assertEquals(0, WALKS.get(), "the measure reset the count");
        cache.listCacheableResources(Measure.FULL_FORCED);
        assertTrue(WALKS.get() > 0);
    }

    @Test
    public void test_a_remembered_total_over_the_cap_measures_everything_before_evicting() throws Exception {
        final var cache = new UserCache();
        measuredIndexIn(cache);
        TestUtils.setPrivateField(memoryManagement, "userCache", cache);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", rememberedBytesOf(cache) / 2);

        memoryManagement.runEvictionSweep();

        assertTrue(WALKS.get() > 0, "an over-budget sweep ranks measured sizes");
        assertNull(cache.residentFieldIndex(DB, COLL, INDEX_KEY));
    }

    @Test
    public void test_signals_while_a_sweep_is_queued_collapse_into_one() throws Exception {
        final var sweeping = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        when(userCache.footprintBytes()).thenAnswer(_ -> {
            sweeping.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return 0L;
        });
        memoryManagement.startSweepThread();
        try {
            final var captor = ArgumentCaptor.forClass(Runnable.class);
            verify(userCache).onOverBudget(captor.capture());
            final var signal = captor.getValue();
            final var scheduler = (ScheduledThreadPoolExecutor) TestUtils.getPrivateField(memoryManagement, "scheduler",
                    ScheduledExecutorService.class);

            signal.run();
            assertTrue(sweeping.await(10, TimeUnit.SECONDS), "a signal wakes the sweep without waiting 5 s");
            final var queuedBefore = scheduler.getQueue().size();
            for (var i = 0; i < 100; i++) {
                signal.run();
            }
            assertEquals(queuedBefore + 1, scheduler.getQueue().size(), "100 signals queue one sweep");
            release.countDown();
            scheduler.submit(() -> {
            }).get(10, TimeUnit.SECONDS);
            verify(userCache, atLeast(2)).footprintBytes();
        } finally {
            release.countDown();
            memoryManagement.stopSweepThread();
        }
        verify(userCache).onOverBudget(null);
    }

    @Test
    public void test_a_requested_sweep_over_the_cap_measures_only_what_drifted() throws Exception {
        rememberedBytes(MAX_BYTES * 2);
        memoryManagement.startSweepThread();
        try {
            final var captor = ArgumentCaptor.forClass(Runnable.class);
            verify(userCache).onOverBudget(captor.capture());
            final var scheduler = TestUtils.getPrivateField(memoryManagement, "scheduler",
                    ScheduledExecutorService.class);

            captor.getValue().run();
            scheduler.submit(() -> {
            }).get(10, TimeUnit.SECONDS);

            verify(userCache).listCacheableResources(Measure.FULL);
            verify(userCache, never()).listCacheableResources(Measure.FULL_FORCED);
        } finally {
            memoryManagement.stopSweepThread();
        }
    }

    @Test
    public void test_an_over_cap_sweep_waits_briefly_for_a_held_collection_lock() throws Exception {
        final var cache = new UserCache();
        measuredIndexIn(cache);
        TestUtils.setPrivateField(memoryManagement, "userCache", cache);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", rememberedBytesOf(cache) / 2);

        holdCollectionLockWhile(true, memoryManagement::runEvictionSweep);

        assertNull(cache.residentFieldIndex(DB, COLL, INDEX_KEY), "a lock released within the budget is waited for");
    }

    @Test
    public void test_an_over_cap_sweep_gives_up_on_a_lock_held_past_the_budget() throws Exception {
        final var cache = new UserCache();
        measuredIndexIn(cache);
        TestUtils.setPrivateField(memoryManagement, "userCache", cache);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", rememberedBytesOf(cache) / 2);

        holdCollectionLockWhile(false, memoryManagement::runEvictionSweep);

        assertNotNull(cache.residentFieldIndex(DB, COLL, INDEX_KEY), "the sweep's lock wait is budgeted");
    }

    @Test
    public void test_a_collection_whose_wait_timed_out_is_skipped_for_the_rest_of_the_sweep() throws Exception {
        final var realLocks = TestUtils.getPrivateField(memoryManagement, "locks", ResourceLocking.class);
        final var locks = mock(ResourceLocking.class);
        when(locks.tryLockWrite(anyString(), anyString(), anyLong())).thenReturn(false);
        when(userCache.listCacheableResources(Measure.FULL_FORCED))
                .thenReturn(List.of(new CacheableResource(AccessKind.PK_INDEX, "userDb", "coll", null, MAX_BYTES),
                        new CacheableResource(AccessKind.COLLECTION, "userDb", "coll", null, MAX_BYTES)));
        rememberedBytes(MAX_BYTES * 2);
        TestUtils.setPrivateField(memoryManagement, "locks", locks);
        try {
            memoryManagement.runEvictionSweep();
        } finally {
            TestUtils.setPrivateField(memoryManagement, "locks", realLocks);
        }

        verify(locks, times(1)).tryLockWrite(eq("userDb"), eq("coll"), anyLong());
    }

    private static void holdCollectionLockWhile(boolean releaseOnceTheSweepWaits, Runnable body) throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        final var sweeper = Thread.currentThread();
        final var held = new CountDownLatch(1);
        final var holder = Thread.ofPlatform().start(() -> {
            try {
                assertTrue(locks.tryLockWrite(DB, COLL, 10_000L));
                try {
                    held.countDown();
                    holdUntil(releaseOnceTheSweepWaits, sweeper);
                } finally {
                    locks.releaseWrite(DB, COLL);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try {
            body.run();
        } finally {
            holder.interrupt();
            holder.join(10_000L);
        }
    }

    private static void holdUntil(boolean releaseOnceTheSweepWaits, Thread sweeper) throws InterruptedException {
        if (!releaseOnceTheSweepWaits) {
            Thread.sleep(10_000L);
            return;
        }
        while (!isWaitingForEvictionLock(sweeper)) {
            Thread.onSpinWait();
        }
    }

    private static boolean isWaitingForEvictionLock(Thread sweeper) {
        return sweeper.getState() == Thread.State.TIMED_WAITING && Arrays.stream(sweeper.getStackTrace())
                .anyMatch(frame -> "lockForEviction".equals(frame.getMethodName()));
    }
}
