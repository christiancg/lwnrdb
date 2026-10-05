package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.AdminLane;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ClusterAdminHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationLocks;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminLaneTest {
    private static final long SHORT_MS = 150L;
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration config = Configuration.getInstance();
    private boolean origEnabled;
    private int origExpected;
    private long origLaneTimeout;
    private long origAckTimeout;
    private Thread laneHolder;
    private CountDownLatch releaseLane;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
        origLaneTimeout = config.getAdminLaneTimeoutMs();
        origAckTimeout = config.getReplicationAckTimeoutMs();
        TestUtils.setPrivateField(config, "adminLaneTimeoutMs", SHORT_MS);
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", SHORT_MS);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(
                new MembershipView(List.of(new NodeInfo("self", "127.0.0.1", 9990, NodeState.ALIVE, 1L, 1L))));
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (releaseLane != null) {
            releaseLane.countDown();
            laneHolder.join(5000);
        }
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        TestUtils.setPrivateField(config, "adminLaneTimeoutMs", origLaneTimeout);
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", origAckTimeout);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static CreateCollectionRequest adminOp() {
        return new CreateCollectionRequest(TestGlobals.DB, "lanecoll");
    }

    private static OperationResponse ok() {
        return new OperationResponse(OperationType.CREATE_COLLECTION, OperationStatus.OK, "ok");
    }

    private void holdTheLaneElsewhere() throws Exception {
        final var entered = new CountDownLatch(1);
        releaseLane = new CountDownLatch(1);
        laneHolder = new Thread(() -> ClusterAdminHelper.inAdminLane(adminOp(), () -> {
            entered.countDown();
            try {
                releaseLane.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok();
        }), "admin-lane-holder");
        laneHolder.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }

    private static OperationResponse onAnotherThread(Supplier<OperationResponse> call) throws Exception {
        return CompletableFuture.supplyAsync(call).get(10, TimeUnit.SECONDS);
    }

    @Test
    public void test_a_second_admin_op_enters_only_after_the_first_finished_replicating() throws Exception {
        final var order = new CopyOnWriteArrayList<String>();
        final var firstEntered = new CountDownLatch(1);
        final var releaseFirst = new CountDownLatch(1);
        TestUtils.setPrivateField(config, "adminLaneTimeoutMs", 5000L);
        final var first = CompletableFuture.supplyAsync(() -> ClusterAdminHelper.inAdminLane(adminOp(), () -> {
            firstEntered.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            order.add("first");
            return ok();
        }));
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
        final var second = CompletableFuture.supplyAsync(() -> ClusterAdminHelper.inAdminLane(adminOp(), () -> {
            order.add("second");
            return ok();
        }));
        Thread.sleep(100);
        assertTrue(order.isEmpty(), "the second admin op must wait for the first to leave the lane");

        releaseFirst.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        assertEquals(List.of("first", "second"), order);
    }

    @Test
    public void test_an_admin_op_that_cannot_enter_in_time_answers_admin_lane_busy() throws Exception {
        holdTheLaneElsewhere();

        final var response = onAnotherThread(() -> ClusterAdminHelper.inAdminLane(adminOp(), AdminLaneTest::ok));

        assertEquals(ErrorCode.ADMIN_LANE_BUSY.getCode(), response.getErrorCode());
    }

    @Test
    public void test_process_message_routes_coordinated_admin_ops_through_the_lane() throws Exception {
        holdTheLaneElsewhere();
        final var processor = IocContainer.get(OperationProcessor.class);

        final var response = onAnotherThread(() -> processor.processMessage(adminOp()));

        assertEquals(ErrorCode.ADMIN_LANE_BUSY.getCode(), response.getErrorCode());
    }

    @Test
    public void test_replicated_and_non_admin_ops_bypass_the_lane() throws Exception {
        holdTheLaneElsewhere();
        final var replicated = adminOp();
        replicated.setReplicated(true);
        final OperationRequest read = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(OperationStatus.OK,
                onAnotherThread(() -> ClusterAdminHelper.inAdminLane(replicated, AdminLaneTest::ok)).getStatus());
        assertEquals(OperationStatus.OK,
                onAnotherThread(() -> ClusterAdminHelper.inAdminLane(read, AdminLaneTest::ok)).getStatus());
    }

    @Test
    public void test_a_standalone_node_never_waits_for_the_lane() throws Exception {
        holdTheLaneElsewhere();
        TestUtils.setPrivateField(config, "clusterEnabled", false);

        assertEquals(OperationStatus.OK,
                onAnotherThread(() -> ClusterAdminHelper.inAdminLane(adminOp(), AdminLaneTest::ok)).getStatus());
    }

    @Test
    public void test_the_lane_is_reentrant_on_its_holder_thread() {
        assertFalse(ClusterAdminHelper.holdsAdminLane());

        final var response = ClusterAdminHelper.inAdminLane(adminOp(), () -> {
            assertTrue(ClusterAdminHelper.holdsAdminLane());
            return ClusterAdminHelper.inAdminLane(adminOp(), AdminLaneTest::ok);
        });

        assertEquals(OperationStatus.OK, response.getStatus());
        assertFalse(ClusterAdminHelper.holdsAdminLane());
    }

    @Test
    public void test_a_lane_holder_never_parks_on_a_held_collection_lock() throws Exception {
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var collectionHolder = new Thread(() -> {
            locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL);
            held.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            }
        });
        collectionHolder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            final var response = ClusterAdminHelper.inAdminLane(adminOp(),
                    () -> OperationLocks.withCollectionLock(TestGlobals.DB, TestGlobals.COLL,
                            OperationType.CREATE_INDEX, ErrorCode.ERROR_CREATING_INDEX, AdminLaneTest::ok));

            assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode(),
                    "an admin op waiting unbounded inside the lane would stall every other admin op");
        } finally {
            release.countDown();
            collectionHolder.join(5000);
        }
    }

    @Test
    public void withinRunsTheOpWhenFree() {
        final var lane = IocContainer.get(AdminLane.class);

        assertEquals("ran", lane.within(SHORT_MS, () -> "ran", () -> "busy"));
    }

    @Test
    public void withinAnswersBusyAfterItsBudget() throws Exception {
        holdTheLaneElsewhere();
        final var lane = IocContainer.get(AdminLane.class);

        final var answer = CompletableFuture.supplyAsync(() -> lane.within(SHORT_MS, () -> "ran", () -> "busy")).get(10,
                TimeUnit.SECONDS);

        assertEquals("busy", answer);
    }

    @Test
    public void withinRestoresTheInterruptFlag() throws Exception {
        holdTheLaneElsewhere();
        final var lane = IocContainer.get(AdminLane.class);

        final var answer = CompletableFuture.supplyAsync(() -> {
            Thread.currentThread().interrupt();
            final var result = lane.within(5_000L, () -> "ran", () -> "busy");
            return result + "/" + Thread.interrupted();
        }).get(10, TimeUnit.SECONDS);

        assertEquals("busy/true", answer);
    }

    @Test
    public void heldByCurrentThreadIsTrueOnlyInsideWithin() {
        final var lane = IocContainer.get(AdminLane.class);

        assertFalse(lane.isHeldByCurrentThread());
        assertTrue(lane.within(SHORT_MS, lane::isHeldByCurrentThread, () -> false));
        assertFalse(lane.isHeldByCurrentThread());
    }
}
