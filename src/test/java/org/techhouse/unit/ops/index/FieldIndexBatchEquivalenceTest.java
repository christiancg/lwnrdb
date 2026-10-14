package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.LegacyIndexMaintenance;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class FieldIndexBatchEquivalenceTest {
    private static final int OPERATIONS = 2000;
    private static final int ID_POOL = 120;
    private static final String LEGACY_FIELD = "legacy";
    private static final String BATCH_FIELD = "batched";

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
        final var random = new Random(seed);
        final var support = new FieldIndexBatchSupport();
        final var legacy = new LegacyIndexMaintenance(IocContainer.get(FileSystem.class), FieldIndexBatchSupport.DB,
                FieldIndexBatchSupport.COLL, LEGACY_FIELD);
        var operations = 0;
        var batches = 0;
        while (operations < OPERATIONS) {
            final var size = 1 + random.nextInt(random.nextBoolean() ? 3 : 60);
            final var ids = FieldIndexBatchSupport.randomIds(random, size, ID_POOL);
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
            support.applyBatch(BATCH_FIELD, ids);

            assertEquals(FieldIndexBatchSupport.snapshot(LEGACY_FIELD), FieldIndexBatchSupport.snapshot(BATCH_FIELD),
                    "seed " + seed + ", batch " + batches + " of ids " + ids);
            assertNull(support.residentMismatch(BATCH_FIELD), "seed " + seed + ", batch " + batches);
        }
    }
}
