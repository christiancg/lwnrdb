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

    private CreateCollectionRequest create(long incarnation, boolean replicated) {
        final var request = new CreateCollectionRequest(TestGlobals.DB, COLLECTION);
        request.setIncarnation(incarnation);
        request.setReplicated(replicated);
        return request;
    }

    @Test
    public void test_a_client_supplied_incarnation_is_replaced_by_a_minted_one() {
        final var request = create(FORGED, false);

        final var response = CollectionOperationHelper.processCreateCollectionOperation(request);

        assertEquals(OperationStatus.OK, response.getStatus());
        final var stored = cache.getAdminCollectionEntry(TestGlobals.DB, COLLECTION).getIncarnation();
        assertNotEquals(FORGED, stored);
        assertTrue(stored > 0);
        assertEquals(stored, request.getIncarnation());
    }

    @Test
    public void test_a_client_supplied_incarnation_does_not_advance_the_clock() {
        CollectionOperationHelper.processCreateCollectionOperation(create(FORGED, false));

        assertTrue(clock.current() < FORGED);
        assertTrue(clock.next() > 0);
    }

    @Test
    public void test_a_replicated_incarnation_is_kept_and_observed() {
        final var replicated = HybridClock.pack(System.currentTimeMillis() + 5000, 7);

        CollectionOperationHelper.processCreateCollectionOperation(create(replicated, true));

        assertEquals(replicated, cache.getAdminCollectionEntry(TestGlobals.DB, COLLECTION).getIncarnation());
        assertTrue(clock.current() >= replicated);
    }

    @Test
    public void test_a_duplicate_client_create_reports_the_existing_incarnation() {
        final var first = create(0, false);
        CollectionOperationHelper.processCreateCollectionOperation(first);
        final var second = create(FORGED, false);

        CollectionOperationHelper.processCreateCollectionOperation(second);

        assertEquals(first.getIncarnation(), second.getIncarnation());
    }
}
