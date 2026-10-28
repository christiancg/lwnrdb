package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class UserCachePkRefusalTest {
    private static final String DB = "refusalDb";
    private static final String COLL = "refusalColl";
    private static final int ROWS = 1000;
    private static final String COLL_ID = Cache.getCollectionIdentifier(DB, COLL);

    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration configuration = Configuration.getInstance();
    private Long savedMaxMemory;
    private UserCache cache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        cache = new UserCache();
        final var fs = mock(FileSystem.class);
        final var rows = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < ROWS; i++) {
            rows.add(new PkIndexEntry(DB, COLL, "row-" + String.format("%05d", i), i * 10L, 10, 0));
        }
        when(fs.readWholePkIndexFile(DB, COLL)).thenReturn(rows);
        TestUtils.setPrivateField(cache, "fs", fs);
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

    private List<PkIndexEntry> loadUnderLock() throws Exception {
        locks.lock(DB, COLL);
        try {
            return cache.getPkIndexAndLoadIfNecessary(DB, COLL);
        } finally {
            locks.release(DB, COLL);
        }
    }

    @Test
    public void aPkThatCannotFitEvenAtItsSmallestIsRefusedWithoutMeasuringIt() throws Exception {
        maxMemory(ROWS * CacheSizeEstimator.minimumPkEntryBytes() - 1);
        try (var estimator = mockStatic(CacheSizeEstimator.class, CALLS_REAL_METHODS)) {
            assertEquals(ROWS, loadUnderLock().size());

            estimator.verify(() -> CacheSizeEstimator.pkIndexBytesPerEntry(anyList()), never());
        }
        assertTrue(cache.pkIndexes().isEmpty());
        final var refused = cache.sizes().refusedPk(COLL_ID);
        assertEquals(ROWS * CacheSizeEstimator.minimumPkEntryBytes(), refused.bytes());
    }

    @Test
    public void aRefusalIsForgottenOnceThePkIsAdmitted() throws Exception {
        maxMemory(1024L);
        loadUnderLock();
        assertNotNull(cache.sizes().refusedPk(COLL_ID));

        maxMemory(64L << 20);
        loadUnderLock();

        assertTrue(cache.pkIndexes().containsKey(COLL_ID));
        assertNull(cache.sizes().refusedPk(COLL_ID));
    }

    @Test
    public void aRefusalIsForgottenWithItsCollectionAndDatabase() throws Exception {
        maxMemory(1024L);
        loadUnderLock();
        cache.evictCollection(DB, COLL);
        assertNull(cache.sizes().refusedPk(COLL_ID));

        loadUnderLock();
        cache.evictDatabase(DB);
        assertNull(cache.sizes().refusedPk(COLL_ID));
    }

    @Test
    public void aRefusalThatNoLongerHoldsLetsTheLockHolderLoadAgain() throws Exception {
        maxMemory(1024L);
        loadUnderLock();
        maxMemory(64L << 20);
        locks.lock(DB, COLL);
        try {
            assertNotNull(cache.pkRowFor(DB, COLL, "row-00007"));
            assertEquals(ROWS, cache.pkCountForCoverage(DB, COLL));
        } finally {
            locks.release(DB, COLL);
        }

        assertTrue(cache.pkIndexes().containsKey(COLL_ID), "a memo is re-checked on every read");
    }
}
