package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

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

public class CollectionIncarnationClockTest {
    private final HybridClock clock = IocContainer.get(HybridClock.class);
    private final Cache cache = IocContainer.get(Cache.class);

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

    @Test
    public void test_a_replicated_create_advances_the_write_clock_past_its_incarnation() {
        final var ahead = clock.next() + 1_000_000_000L;
        final var request = new CreateCollectionRequest(TestGlobals.DB, "replicated_coll");
        request.setReplicated(true);
        request.setIncarnation(ahead);

        assertEquals(OperationStatus.OK,
                CollectionOperationHelper.processCreateCollectionOperation(request).getStatus());

        assertEquals(ahead, cache.getAdminCollectionEntry(TestGlobals.DB, "replicated_coll").getIncarnation());
        assertTrue(clock.next() > ahead,
                "a coordinator taking over later must mint an incarnation above every one it has seen");
    }

    @Test
    public void test_a_local_create_mints_an_incarnation_from_the_clock() {
        final var before = clock.current();

        assertEquals(OperationStatus.OK,
                CollectionOperationHelper
                        .processCreateCollectionOperation(new CreateCollectionRequest(TestGlobals.DB, "local_coll"))
                        .getStatus());

        assertTrue(cache.getAdminCollectionEntry(TestGlobals.DB, "local_coll").getIncarnation() > before);
    }
}
