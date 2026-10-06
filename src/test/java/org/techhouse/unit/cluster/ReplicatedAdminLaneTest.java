package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.AdminLane;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReplicatedAdminLaneTest {
    private static final long ACK_TIMEOUT_MS = 1_000L;
    private static final long REQUEST_TIMEOUT_MS = 10_000L;
    private static final long NEXT_EPOCH = 1L;
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final PeerConnectionPool pool = new PeerConnectionPool();
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final AdminLane adminLane = IocContainer.get(AdminLane.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private Thread laneHolder;
    private CountDownLatch releaseLane;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cluster.start(true, ACK_TIMEOUT_MS);
        cluster.configureMembership(1,
                new NodeInfo("self", "127.0.0.1", cluster.serverPort(), NodeState.ALIVE, 1L, 1L));
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        releaseTheLane();
        pool.closeAll();
        cluster.stop();
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void holdTheLaneElsewhere() throws Exception {
        final var entered = new CountDownLatch(1);
        releaseLane = new CountDownLatch(1);
        laneHolder = new Thread(() -> adminLane.within(REQUEST_TIMEOUT_MS, () -> {
            entered.countDown();
            try {
                releaseLane.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }, () -> null), "conform-lane-holder");
        laneHolder.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }

    private void releaseTheLane() throws InterruptedException {
        if (releaseLane != null) {
            releaseLane.countDown();
            laneHolder.join(5000);
            releaseLane = null;
        }
    }

    private static ClusterMessage envelope(ClusterMessageType type) {
        final var message = new ClusterMessage();
        message.setType(type);
        message.setSecret(ClusterTestHarness.SECRET);
        message.setAdminEpoch(NEXT_EPOCH);
        return message;
    }

    private static ClusterMessage replicatedCreate(String collName) {
        final var message = envelope(ClusterMessageType.REPLICATE_ADMIN);
        message.setForwardBody(ForwardBody.encode("{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + collName + "\"}"));
        return message;
    }

    private static ClusterMessage replicatedUser() {
        final var user = new AdminUserEntry("laneuser", "hash", false, Set.of(), Map.of(), Map.of());
        final var message = envelope(ClusterMessageType.REPLICATE_USER);
        message.setReplication(new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME,
                ReplicationOp.UPSERT, List.of(user.getData()), null));
        return message;
    }

    @Test
    public void aReplicatedAdminOpWaitsForAConformAndAppliesAfterIt() throws Exception {
        holdTheLaneElsewhere();
        final var pending = CompletableFuture.supplyAsync(() -> requestQuietly(replicatedCreate("afterconform")));
        Thread.sleep(ACK_TIMEOUT_MS / 5);
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "afterconform"),
                "a replicated admin op must not apply while the conform holds the lane");
        releaseTheLane();

        final var ack = pending.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertEquals(ClusterMessageType.REPLICATE_ADMIN_ACK, ack.getType(), ack.getErrorMessage());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "afterconform"));
        assertEquals(NEXT_EPOCH, adminEpoch.current());
    }

    @Test
    public void aReplicatedAdminOpAnswersErrorWhenTheLaneStaysBusy() throws Exception {
        holdTheLaneElsewhere();

        final var ack = pool.request(cluster.serverAddress(), replicatedCreate("busycoll"), REQUEST_TIMEOUT_MS);

        assertEquals(ClusterMessageType.ERROR, ack.getType());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "busycoll"));
        assertEquals(0L, adminEpoch.current(), "a refused replicated op must not adopt its epoch");
    }

    @Test
    public void aReplicatedUserOpAnswersErrorWhenTheLaneStaysBusy() throws Exception {
        holdTheLaneElsewhere();

        final var ack = pool.request(cluster.serverAddress(), replicatedUser(), REQUEST_TIMEOUT_MS);

        assertEquals(ClusterMessageType.ERROR, ack.getType());
        assertNull(cache.getAdminUserEntry("laneuser"));
        assertEquals(0L, adminEpoch.current());
    }

    private ClusterMessage requestQuietly(ClusterMessage message) {
        try {
            return pool.request(cluster.serverAddress(), message, REQUEST_TIMEOUT_MS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
