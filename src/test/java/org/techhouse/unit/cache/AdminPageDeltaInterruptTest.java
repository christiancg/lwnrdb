package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminPageDeltaInterruptTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static String pagesCollName() {
        return String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, TestGlobals.DB, TestGlobals.COLL);
    }

    private long recordedPageSize(long page) {
        for (final var entry : cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL)) {
            if (entry.getPage() == page) {
                return entry.getPageSize();
            }
        }
        return -1L;
    }

    @Test
    public void test_an_interrupted_thread_still_applies_its_page_size_delta() throws Exception {
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);
        final var before = recordedPageSize(0);

        final var applied = new AtomicLong(-1);
        final var stillInterrupted = new AtomicBoolean(false);
        final var worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 40);
            stillInterrupted.set(Thread.currentThread().isInterrupted());
            applied.set(recordedPageSize(0));
        });
        worker.start();
        worker.join(10_000);

        assertEquals(before + 40, applied.get(),
                "a lost page size update is permanent, so an interrupt must not discard the delta");
        assertTrue(stillInterrupted.get(), "the interrupt must be restored once the delta has landed");
    }

    @Test
    public void test_the_delta_is_dropped_loudly_when_the_lock_cannot_be_taken_at_all() throws Exception {
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);
        final var before = recordedPageSize(0);

        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = new Thread(() -> {
            try {
                locks.lock(Globals.ADMIN_PAGES_DB_NAME, pagesCollName());
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.release(Globals.ADMIN_PAGES_DB_NAME, pagesCollName());
            }
        });
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS), "the holder must take the admin_pages lock first");

        try {
            final var worker = new Thread(() -> {
                Thread.currentThread().interrupt();
                cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 40);
            });
            worker.start();
            worker.join(10_000);
            assertFalse(worker.isAlive(), "the bounded retry must give up rather than spin forever");
            assertEquals(before, recordedPageSize(0),
                    "with the lock unavailable the delta is refused rather than applied unlocked");
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    @Test
    public void test_an_uninterrupted_delta_takes_the_ordinary_path() {
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 1, 70);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 1, 30);

        assertEquals(100, recordedPageSize(1), "successive deltas accumulate on the same page row");
        assertFalse(Thread.currentThread().isInterrupted(), "the ordinary path never sets the interrupt flag");
    }
}
