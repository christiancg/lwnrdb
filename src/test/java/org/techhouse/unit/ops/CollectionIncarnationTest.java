package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.EJson;
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

    @Test
    public void test_an_incarnation_with_a_logical_counter_survives_the_wire() {
        final var eJson = new EJson();
        for (final var logical : new long[]{0L, 1L, 5L, 255L, 65535L}) {
            final var packed = (System.currentTimeMillis() << 16) | logical;
            final var request = new CreateCollectionRequest(TestGlobals.DB, "wireIncColl");
            request.setIncarnation(packed);

            final var back = eJson.fromJson(eJson.toJson(request), CreateCollectionRequest.class);

            assertEquals(packed, back.getIncarnation(),
                    "an HLC incarnation is ~2^57, so a JSON number round trip through a double quantises the"
                            + " whole 16-bit logical counter and the conform then quarantines a healthy collection");
        }
    }

    @Test
    public void test_a_duplicate_create_replicates_the_existing_incarnation() {
        final var first = new CreateCollectionRequest(TestGlobals.DB, "dupIncColl");
        assertEquals(OperationStatus.OK, processor.processMessage(first).getStatus());

        final var second = new CreateCollectionRequest(TestGlobals.DB, "dupIncColl");
        assertEquals(OperationStatus.OK, processor.processMessage(second).getStatus());

        assertEquals(first.getIncarnation(), second.getIncarnation(),
                "a create on an existing collection still replicates, so shipping incarnation 0 would let a peer"
                        + " that lacks the collection mint a higher one and quarantine every populated copy");
    }

    @Test
    public void test_a_replicated_create_does_not_mint_an_incarnation() {
        final var request = new CreateCollectionRequest(TestGlobals.DB, "replicatedZeroColl");
        request.setReplicated(true);

        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());

        assertEquals(0L, IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, "replicatedZeroColl")
                .getIncarnation(), "a replica must adopt the coordinator's value, never derive one locally");
    }

    @Test
    public void test_an_absent_incarnation_reads_as_zero() {
        final var request = new CreateCollectionRequest(TestGlobals.DB, "absentIncColl");

        assertEquals(0L, request.getIncarnation(),
                "zero is the documented predates-the-field value the conform adopts rather than quarantines");
    }

    @Test
    public void test_a_numeric_incarnation_from_an_older_peer_still_parses() {
        final var eJson = new EJson();
        final var stamped = new CreateCollectionRequest(TestGlobals.DB, "oldPeerColl");
        stamped.setIncarnation(117309440008060933L);
        final var fromOldPeer = eJson.toJson(stamped).replaceAll(",\"incarnationText\":\"[0-9]+\"", "");

        final var request = eJson.fromJson(fromOldPeer, CreateCollectionRequest.class);

        assertNotNull(request,
                "an older peer sends only the numeric field; refusing to parse it would stop CREATE_COLLECTION"
                        + " replicating at all during a rolling upgrade");
        assertEquals(117309440008060928L, request.getIncarnation(),
                "the numeric fallback is still lossy, which is exactly the pre-upgrade behaviour and no worse");
    }

    @Test
    public void test_the_wire_carries_both_the_numeric_and_the_text_incarnation() {
        final var eJson = new EJson();
        final var request = new CreateCollectionRequest(TestGlobals.DB, "bothFieldsColl");
        request.setIncarnation(117309440008060933L);

        final var json = eJson.toJson(request);

        assertTrue(json.contains("\"incarnation\":117309440008060933"),
                "the numeric field stays so an older peer can still read it");
        assertTrue(json.contains("\"incarnationText\":\"117309440008060933\""),
                "the text field is the authoritative one and is exact");
    }
}
