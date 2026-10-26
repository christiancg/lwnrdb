package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.LegacyIndexMaintenance;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class FieldIndexMergeEquivalenceTest {
    private static final int ROUNDS = 20;
    private static final int ID_POOL = 1500;
    private static final int DISTINCT_VALUES = 2500;
    private static final String LEGACY_FIELD = "legacyMerge";
    private static final String BATCH_FIELD = "batchedMerge";

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

    private static void assign(FieldIndexBatchSupport support, String id, Random random) {
        final var roll = random.nextInt(20);
        if (roll < 9) {
            support.put(id, new JsonNumber(random.nextInt(DISTINCT_VALUES) / 8.0));
        } else if (roll < 13) {
            final var key = "Key" + random.nextInt(DISTINCT_VALUES);
            support.put(id, new JsonString(random.nextBoolean() ? key : key.toLowerCase(Locale.ROOT)));
        } else if (roll < 14) {
            support.put(id, JsonBoolean.of(random.nextBoolean()));
        } else if (roll < 15) {
            support.put(id, new JsonNumber(random.nextBoolean() ? -0.0 : 0.0));
        } else if (roll < 17) {
            support.delete(id);
        } else {
            support.mutate(id, random);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {5L, 20261022L, 777L})
    public void batchesChangingManyDistinctValuesMatchTheLegacyResult(long seed) throws Exception {
        final var random = new Random(seed);
        final var support = new FieldIndexBatchSupport();
        final var legacy = new LegacyIndexMaintenance(IocContainer.get(FileSystem.class), FieldIndexBatchSupport.DB,
                FieldIndexBatchSupport.COLL, LEGACY_FIELD);
        for (var round = 1; round <= ROUNDS; round++) {
            final var ids = FieldIndexBatchSupport.randomIds(random, 100 + random.nextInt(500), ID_POOL);
            for (final var id : ids) {
                assign(support, id, random);
            }
            if (random.nextInt(3) == 0) {
                support.admitEveryType(BATCH_FIELD);
            }
            for (final var id : ids) {
                legacy.apply(id, support.documentOf(id, LEGACY_FIELD));
            }

            support.applyBatchHoldingTheCollection(BATCH_FIELD, ids);

            assertEquals(FieldIndexBatchSupport.snapshot(LEGACY_FIELD), FieldIndexBatchSupport.snapshot(BATCH_FIELD),
                    "seed " + seed + ", round " + round);
            assertNull(support.residentMismatch(BATCH_FIELD), "seed " + seed + ", round " + round);
            assertNull(support.lookupMismatch(BATCH_FIELD, ID_POOL), "seed " + seed + ", round " + round);
        }
    }
}
