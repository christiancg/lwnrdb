package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateCollectionRequest;
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
    public void test_create_collection_stamps_an_incarnation() {
        final var request = new CreateCollectionRequest(TestGlobals.DB, "incarnationColl");

        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());

        assertTrue(request.getIncarnation() > 0,
                "the coordinator stamps the incarnation onto the request so the same value reaches every replica"
                        + " through re-execution");
        final var entry = IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, "incarnationColl");
        assertEquals(request.getIncarnation(), entry.getIncarnation());
    }

    @Test
    public void test_a_replicated_create_keeps_the_coordinators_incarnation() {
        final var request = new CreateCollectionRequest(TestGlobals.DB, "replicatedIncColl");
        request.setIncarnation(4242L);

        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());

        assertEquals(4242L,
                IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, "replicatedIncColl")
                        .getIncarnation(),
                "a re-executed create must not mint its own incarnation, or every node would disagree and"
                        + " quarantine each other's collections");
    }
}
