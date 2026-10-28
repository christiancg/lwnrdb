package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.LegacyIndexMaintenance;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.IndexBuild;
import org.techhouse.test.TestUtils;

public class FieldIndexBatchEquivalenceTest {
    private static final int OPERATIONS = 2000;
    private static final int ID_POOL = 120;
    private static final String LEGACY_FIELD = "legacy";
    private static final String BATCH_FIELD = "batched";
    private static final String BUILT_FIELD = "built";

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

    @ParameterizedTest
    @ValueSource(longs = {1L, 42L, 20261013L})
    public void batchResultEqualsLegacyPerIdResult(long seed) throws Exception {
        runAgainstLegacy(seed, false);
    }

    @ParameterizedTest
    @ValueSource(longs = {7L, 20261014L})
    public void aCachedLookupStaysEqualToTheFilesAcrossBatches(long seed) throws Exception {
        runAgainstLegacy(seed, true);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 40, 300})
    public void bothSidesOfThePointEditLimitMatchLegacy(int batchSize) throws Exception {
        runAgainstLegacy(20261019L + batchSize, true, batchSize, batchSize * 2);
    }

    @ParameterizedTest
    @ValueSource(longs = {3L, 20261025L})
    public void aBuiltFieldEqualsTheBatchMaintainedOne(long seed) throws Exception {
        final var random = new Random(seed);
        final var support = new FieldIndexBatchSupport();
        for (var batch = 0; batch < 40; batch++) {
            final var ids = FieldIndexBatchSupport.randomIds(random, 1 + random.nextInt(30), ID_POOL);
            for (final var id : ids) {
                support.mutate(id, random);
            }
            support.applyBatchHoldingTheCollection(BATCH_FIELD, ids);
        }
        final var cache = IocContainer.get(Cache.class);
        for (var i = 0; i < ID_POOL; i++) {
            final var document = support.documentOf("id" + i, BUILT_FIELD);
            if (document != null) {
                TestUtils.cacheEntry(cache, FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, document);
            }
        }
        final var locks = IocContainer.get(ResourceLocking.class);
        locks.lock(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL);
        try {
            IndexBuild.buildAll(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, List.of(BUILT_FIELD));
        } finally {
            locks.release(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL);
        }

        assertEquals(FieldIndexBatchSupport.snapshot(BATCH_FIELD), FieldIndexBatchSupport.snapshot(BUILT_FIELD),
                "seed " + seed);
    }

    private void runAgainstLegacy(long seed, boolean holdingTheCollection) throws Exception {
        runAgainstLegacy(seed, holdingTheCollection, 0, ID_POOL);
    }

    private void runAgainstLegacy(long seed, boolean holdingTheCollection, int fixedBatchSize, int idPool)
            throws Exception {
        final var random = new Random(seed);
        final var support = new FieldIndexBatchSupport();
        final var legacy = new LegacyIndexMaintenance(IocContainer.get(FileSystem.class), FieldIndexBatchSupport.DB,
                FieldIndexBatchSupport.COLL, LEGACY_FIELD);
        var operations = 0;
        var batches = 0;
        while (operations < OPERATIONS) {
            final var size = fixedBatchSize > 0 ? fixedBatchSize : 1 + random.nextInt(random.nextBoolean() ? 3 : 60);
            final var ids = FieldIndexBatchSupport.randomIds(random, size, idPool);
            for (final var id : ids) {
                support.mutate(id, random);
            }
            operations += size;
            batches++;
            if (random.nextInt(4) == 0) {
                support.admitEveryType(BATCH_FIELD);
            }
            for (final var id : ids) {
                legacy.apply(id, support.documentOf(id, LEGACY_FIELD));
            }
            if (holdingTheCollection) {
                support.applyBatchHoldingTheCollection(BATCH_FIELD, ids);
            } else {
                support.applyBatch(BATCH_FIELD, ids);
            }

            assertEquals(FieldIndexBatchSupport.snapshot(LEGACY_FIELD), FieldIndexBatchSupport.snapshot(BATCH_FIELD),
                    "seed " + seed + ", batch " + batches + " of ids " + ids);
            assertNull(support.residentMismatch(BATCH_FIELD), "seed " + seed + ", batch " + batches);
            assertNull(support.lookupMismatch(BATCH_FIELD, idPool), "seed " + seed + ", batch " + batches);
        }
    }
}
