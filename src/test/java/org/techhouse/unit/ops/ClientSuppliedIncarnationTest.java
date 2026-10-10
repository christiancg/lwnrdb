package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.HybridClock;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.admin.CollectionOperationHelper;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClientSuppliedIncarnationTest {
    private static final String COLLECTION = "incarnation_target";
    private static final long FORGED = Long.MAX_VALUE - 1000;
    private final Cache cache = IocContainer.get(Cache.class);
    private final HybridClock clock = IocContainer.get(HybridClock.class);

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static CreateCollectionRequest create(long incarnation) {
        return (CreateCollectionRequest) RequestParser
                .parseRequest("{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"" + TestGlobals.DB
                        + "\",\"collectionName\":\"" + COLLECTION + "\",\"incarnation\":" + incarnation
                        + ",\"incarnationText\":\"" + incarnation + "\"}");
    }

    private long storedIncarnation() {
        return cache.getAdminCollectionEntry(TestGlobals.DB, COLLECTION).getIncarnation();
    }

    @Test
    public void test_a_client_supplied_incarnation_is_ignored_and_one_is_minted() {
        final var response = CollectionOperationHelper.processCreateCollectionOperation(create(FORGED));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotEquals(FORGED, storedIncarnation());
        assertTrue(storedIncarnation() > 0);
    }

    @Test
    public void test_a_client_supplied_incarnation_does_not_advance_the_clock() {
        CollectionOperationHelper.processCreateCollectionOperation(create(FORGED));

        assertTrue(clock.current() < FORGED);
        assertTrue(clock.next() > 0);
    }

    @Test
    public void test_a_duplicate_client_create_keeps_the_existing_incarnation() {
        CollectionOperationHelper.processCreateCollectionOperation(create(0));
        final var first = storedIncarnation();

        CollectionOperationHelper.processCreateCollectionOperation(create(FORGED));

        assertEquals(first, storedIncarnation());
    }
}
