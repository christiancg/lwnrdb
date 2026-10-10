package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OnDiskNameRegistry;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CollectionIncarnationTest {
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    public static void tearDownAll() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_create_collection_mints_an_incarnation() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "incarnationColl")).getStatus());

        assertTrue(IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, "incarnationColl")
                .getIncarnation() > 0);
    }

    @Test
    public void test_a_duplicate_create_keeps_the_existing_incarnation() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "dupIncColl")).getStatus());
        final var first = IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, "dupIncColl")
                .getIncarnation();

        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "dupIncColl")).getStatus());

        assertEquals(first,
                IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, "dupIncColl").getIncarnation());
    }

    @Test
    public void test_case_differing_collection_is_refused() {
        final var created = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "caseProbe"));
        assertEquals(OperationStatus.OK, created.getStatus());
        final var colliding = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "CaseProbe"));
        assertEquals(ErrorCode.NAME_COLLIDES_ON_DISK.getCode(), colliding.getErrorCode());
    }

    @Test
    public void test_recreating_the_same_collection_name_is_still_idempotent() {
        final var first = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "idemProbe"));
        assertEquals(OperationStatus.OK, first.getStatus());
        final var second = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "idemProbe"));
        assertEquals(OperationStatus.OK, second.getStatus());
    }

    @Test
    public void test_dropping_then_recreating_a_collection_name_is_not_a_collision() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "dropProbe")).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.DB, "dropProbe")).getStatus());
        final var recreated = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "dropProbe"));
        assertEquals(OperationStatus.OK, recreated.getStatus());
        assertNull(OnDiskNameRegistry.collidingCollection(TestGlobals.DB, "dropProbe"));
    }

    @Test
    public void test_a_dropped_collection_name_frees_its_case_variants_too() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "freedProbe")).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.DB, "freedProbe")).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "FreedProbe")).getStatus());
    }
}
