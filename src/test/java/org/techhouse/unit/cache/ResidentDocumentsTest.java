package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.CacheResidents;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.cache.UserCache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class ResidentDocumentsTest {
    private static final String DB = "residentDb";
    private static final String COLL = "residentColl";
    private static final int IDS = 64;

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

    private static DbEntry document(String id) {
        final var data = new JsonObject();
        data.addProperty("_id", id);
        data.addProperty("payload", "x".repeat(40));
        return DbEntry.fromJsonObject(DB, COLL, data);
    }

    private static UserCache cacheReadingDocuments() throws Exception {
        final var cache = new UserCache();
        final var fs = mock(FileSystem.class);
        when(fs.getById(any(PkIndexEntry.class))).thenAnswer(call -> {
            final PkIndexEntry row = call.getArgument(0);
            return document(row.getValue());
        });
        TestUtils.setPrivateField(cache, "fs", fs);
        return cache;
    }

    @Test
    public void readersRacingDocumentEvictionsLeaveNoResidueInTheTotal() throws Exception {
        final var cache = cacheReadingDocuments();
        final var stop = new AtomicBoolean();
        final var start = new CountDownLatch(1);
        final var readers = new ArrayList<Thread>();
        for (var reader = 0; reader < 4; reader++) {
            final var offset = reader;
            readers.add(Thread.ofPlatform().start(() -> readUntilStopped(cache, start, stop, offset)));
        }
        final var evictor = Thread.ofPlatform().start(() -> {
            awaitQuietly(start);
            while (!stop.get()) {
                cache.evictCollectionDocuments(DB, COLL);
            }
        });
        start.countDown();
        Thread.sleep(300L);
        stop.set(true);
        for (final var thread : readers) {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        }
        evictor.join(TimeUnit.SECONDS.toMillis(10));

        final var resident = CacheResidents.residentDocuments(cache,
                org.techhouse.cache.Cache.getCollectionIdentifier(DB, COLL));
        final var expected = resident == null ? 0L : CacheSizeEstimator.estimateCollectionEntries(resident.values());
        assertEquals(expected, CacheResidents.footprint(cache, AccessKind.COLLECTION),
                "the total equals the documents still resident");
        cache.evictCollectionDocuments(DB, COLL);
        assertEquals(0L, CacheResidents.footprint(cache, AccessKind.COLLECTION),
                "a late put into an evicted map drained instead of leaking");
        assertTrue(cache.footprintBytes() >= 0L);
    }

    private void readUntilStopped(UserCache cache, CountDownLatch start, AtomicBoolean stop, int offset) {
        awaitQuietly(start);
        var i = offset;
        while (!stop.get()) {
            final var id = "doc-" + (i++ % IDS);
            try {
                locks.lockRead(DB, COLL);
                try {
                    cache.getById(DB, COLL, new PkIndexEntry(DB, COLL, id, 0, 10, 0));
                } finally {
                    locks.releaseRead(DB, COLL);
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
