package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.Replicator;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.msg.TxReplicationPayload;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class GroupCommitClusterTest {
    private static final String SUBJECT = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Configuration config = Configuration.getInstance();
    private final Replicator replicator = mock(Replicator.class);
    private Replicator originalReplicator;
    private IntConsumer originalHalt;
    private boolean originalEnabled;
    private int originalExpected;
    private RecordingForcer recorder;
    private GroupCommitFixture fixture;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalEnabled = config.isClusterEnabled();
        originalExpected = config.getClusterExpectedSize();
        originalHalt = TestUtils.getPrivateField(faultInjector, "halt", IntConsumer.class);
        originalReplicator = TestUtils.getPrivateField(coordinator, "replicator", Replicator.class);
        TestUtils.setPrivateField(coordinator, "replicator", replicator);
        when(replicator.broadcast(any())).thenReturn(ReplicationOutcome.QUORUM_MET);
        when(replicator.broadcastTx(any())).thenReturn(ReplicationOutcome.QUORUM_MET);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        final var self = node("self", 9990);
        members.put(self.getNodeId(), self);
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
        ownership.setSelfNodeId(self.getNodeId());
        ownership.onMembershipChanged(membershipService.membershipView());
        recorder = RecordingForcer.install(true);
        fixture = new GroupCommitFixture(recorder);
        assertEquals(OperationStatus.OK, fixture.save("seed", "v0").getStatus());
        clearInvocations(replicator);
    }

    @AfterEach
    public void tearDown() throws Exception {
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
        TestUtils.setPrivateField(faultInjector, "halt", originalHalt);
        recorder.close();
        TestUtils.setPrivateField(coordinator, "replicator", originalReplicator);
        TestUtils.setPrivateField(config, "clusterEnabled", originalEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", originalExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static List<String> codes(List<OperationResponse> responses) {
        return responses.stream().map(OperationResponse::getErrorCode).toList();
    }

    private void loseOwnership() {
        ownership.onMembershipChanged(new MembershipView(List.of(node("other", 9991))));
    }

    @Test
    public void test_a_group_ships_one_replicate() throws Exception {
        final var responses = fixture.runQueued(GroupCommitFixture.times(4, number -> fixture.save("g" + number, "v")));

        assertTrue(responses.stream().allMatch(response -> response.getStatus() == OperationStatus.OK),
                String.valueOf(codes(responses)));
        final var payload = ArgumentCaptor.forClass(ReplicationPayload.class);
        verify(replicator).broadcast(payload.capture());
        verify(replicator, never()).broadcastTx(any());
        assertEquals(ReplicationOp.UPSERT, payload.getValue().getOp());
        assertEquals(4, payload.getValue().getDocuments().size());
        assertEquals(4, payload.getValue().getVersions().size());
        assertEquals(1L, fixture.walForces(), "the seed logged the group base, so one force for the whole group");
    }

    @Test
    public void test_a_mixed_group_ships_one_replicate_tx_deletes_first() throws Exception {
        final var responses = fixture.runQueued(List.of(() -> fixture.save("a", "v"), () -> fixture.delete("seed"),
                () -> fixture.save("b", "v"), () -> fixture.save("gone", "v"), () -> fixture.delete("gone")));

        assertTrue(responses.stream().allMatch(response -> response.getStatus() == OperationStatus.OK),
                String.valueOf(codes(responses)));
        final var payload = ArgumentCaptor.forClass(TxReplicationPayload.class);
        verify(replicator).broadcastTx(payload.capture());
        verify(replicator, never()).broadcast(any());
        final var entries = payload.getValue().getEntries();
        assertEquals(List.of(ReplicationOp.DELETE, ReplicationOp.UPSERT),
                entries.stream().map(ReplicationPayload::getOp).toList());
        assertEquals(List.of("seed", "gone"), entries.getFirst().getIds());
        assertEquals(
                List.of("a", "b"), entries.get(1).getDocuments().stream()
                        .map(document -> document.get("_id").asJsonString().getValue()).toList(),
                "an id deleted later in the group ships only its delete");
        assertTrue(entries.stream().allMatch(entry -> entry.getIncarnation() != null));
    }

    @Test
    public void test_a_timeout_answers_503_3_to_every_applied_writer() throws Exception {
        when(replicator.broadcast(any())).thenReturn(ReplicationOutcome.TIMEOUT);

        final var responses = fixture.runQueued(GroupCommitFixture.times(3, number -> fixture.save("t" + number, "v")));

        assertEquals(List.of("503-3", "503-3", "503-3"), codes(responses));
        assertEquals("v", fixture.valueOf("t2"), "the local commit stands");
    }

    @Test
    public void test_lost_ownership_answers_503_8() throws Exception {
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        faultInjector.arm(FaultPoint.WAL_GROUP_APPENDED, FaultAction.PAUSE, SUBJECT);

        final var group = fixture
                .runQueuedInBackground(GroupCommitFixture.times(3, number -> fixture.save("o" + number, "v")));
        assertTrue(faultInjector.awaitReached(FaultPoint.WAL_GROUP_APPENDED, GroupCommitFixture.WAIT_MS));
        loseOwnership();
        faultInjector.release(FaultPoint.WAL_GROUP_APPENDED);

        assertEquals(List.of("503-8", "503-8", "503-8"),
                codes(group.get(GroupCommitFixture.WAIT_MS, TimeUnit.MILLISECONDS)));
        verify(replicator, never()).broadcast(any());
    }

    @Test
    public void test_a_refused_writer_keeps_its_421() throws Exception {
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        faultInjector.arm(FaultPoint.WRITE_OWNER_PROBED, FaultAction.PAUSE, SUBJECT);

        final var group = fixture
                .runQueuedInBackground(GroupCommitFixture.times(3, number -> fixture.save("r" + number, "v")));
        assertTrue(faultInjector.awaitReached(FaultPoint.WRITE_OWNER_PROBED, GroupCommitFixture.WAIT_MS));
        loseOwnership();
        faultInjector.release(FaultPoint.WRITE_OWNER_PROBED);

        assertEquals(List.of("503-8", "421-1", "421-1"),
                codes(group.get(GroupCommitFixture.WAIT_MS, TimeUnit.MILLISECONDS)));
    }

    @Test
    public void test_a_taken_back_group_retracts_its_tombstones() throws Exception {
        assertEquals(OperationStatus.OK, fixture.save("other", "v").getStatus());
        assertTrue(fixture.runQueued(GroupCommitFixture.times(2, number -> fixture.save("base" + number, "v"))).stream()
                .allMatch(response -> response.getStatus() == OperationStatus.OK), "logs the group base");
        recorder.failingFor(GroupCommitFixture.WAL);

        final var responses = fixture.runQueued(List.of(() -> fixture.delete("seed"), () -> fixture.delete("other")));
        recorder.failingFor(null);

        final var deleting = ErrorCode.ERROR_DELETING.getCode();
        assertEquals(List.of(deleting, deleting), codes(responses));
        final var tombstones = fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL);
        assertFalse(tombstones.containsKey("seed"), String.valueOf(tombstones));
        assertFalse(tombstones.containsKey("other"));
        assertEquals("v0", fixture.valueOf("seed"));
    }

    @Test
    public void test_replicate_group_edge_outcomes() throws Exception {
        assertEquals(ReplicationOutcome.QUORUM_MET,
                coordinator.replicateGroup(TestGlobals.DB, TestGlobals.COLL, List.of("absent"), Map.of()),
                "nothing left to ship needs no acks");
        verify(replicator, never()).broadcast(any());
        final var nullVersion = new HashMap<String, Long>();
        nullVersion.put("seed", null);
        assertEquals(ReplicationOutcome.QUORUM_MET,
                coordinator.replicateGroup(TestGlobals.DB, TestGlobals.COLL, List.of(), nullVersion));
        final var payload = ArgumentCaptor.forClass(ReplicationPayload.class);
        verify(replicator).broadcast(payload.capture());
        assertTrue(Long.parseLong(payload.getValue().getVersions().getFirst()) > 0L, "a missing version is minted");
        when(replicator.broadcast(any())).thenThrow(new IllegalStateException("peer pool closed"));
        assertEquals(ReplicationOutcome.TIMEOUT,
                coordinator.replicateGroup(TestGlobals.DB, TestGlobals.COLL, List.of("seed"), Map.of()));
        loseOwnership();
        assertEquals(ReplicationOutcome.NOT_OWNER,
                coordinator.replicateGroup(TestGlobals.DB, TestGlobals.COLL, List.of("seed"), Map.of()));
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        assertEquals(ReplicationOutcome.NOT_CLUSTERED,
                coordinator.replicateGroup(TestGlobals.DB, TestGlobals.COLL, List.of("seed"), Map.of()));
    }
}
