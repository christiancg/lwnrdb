package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
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
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.resp.DeleteManyResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class ConditionalDeleteClusterTest {
    private static final String ACTOR = "alice";
    private static final long WAIT_MS = 10_000L;
    private static final Set<String> DOOMED = Set.of("c0", "c1", "c2");
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Configuration config = Configuration.getInstance();
    private final Replicator replicator = mock(Replicator.class);
    private final List<TriggerEvent> dispatched = new CopyOnWriteArrayList<>();
    private Replicator originalReplicator;
    private boolean originalEnabled;
    private int originalExpected;
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalEnabled = config.isClusterEnabled();
        originalExpected = config.getClusterExpectedSize();
        originalReplicator = TestUtils.getPrivateField(coordinator, "replicator", Replicator.class);
        TestUtils.setPrivateField(coordinator, "replicator", replicator);
        when(replicator.broadcast(any())).thenReturn(ReplicationOutcome.QUORUM_MET);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        final var self = new NodeInfo("self", "127.0.0.1", 9990, NodeState.ALIVE, 1L, 1L);
        members.put(self.getNodeId(), self);
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
        ownership.setSelfNodeId(self.getNodeId());
        ownership.onMembershipChanged(membershipService.membershipView());
        TestUtils.setPrivateField(config, "triggersEnabled", true);
        TestUtils.setPrivateField(config, "triggerRunLogEnabled", true);
        triggerExecutor.stop();
        TriggerTestSupport.consumeAllPendingRuns();
        triggerExecutor.start(dispatched::add);
        seed();
        installDeletedTrigger();
        clearInvocations(replicator);
        recorder = RecordingForcer.install(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        recorder.close();
        triggerExecutor.stop();
        TriggerTestSupport.consumeAllPendingRuns();
        TriggerTestSupport.clearTriggersAndProcedures();
        TestUtils.setPrivateField(config, "triggersEnabled", false);
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

    private void seed() {
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var documents = new ArrayList<JsonObject>();
        for (var i = 0; i < 6; i++) {
            final var document = new JsonObject();
            document.add(Globals.PK_FIELD, new JsonString("c" + i));
            document.add("n", new JsonNumber(i));
            documents.add(document);
        }
        request.setObjects(documents);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void installDeletedTrigger() throws Exception {
        assertEquals(OperationStatus.OK, ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "audit", "return 1;"), ACTOR).getStatus());
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.DELETED)), "audit",
                        TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_AFTER, false, true, ACTOR, 1L, 1L, 1L,
                        ACTOR)));
    }

    private OperationResponse deleteWhereNBelowThree() {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setCondition(new FieldOperator(FieldOperatorType.SMALLER_THAN, "n", new JsonNumber(3)));
        return processor.processMessage(request);
    }

    private Set<String> stored() {
        return new TreeSet<>(cache.getWholeCollection(TestGlobals.DB, TestGlobals.COLL).keySet());
    }

    private List<TriggerEvent> awaitDispatches() {
        assertTrue(triggerExecutor.drain(WAIT_MS), "the trigger queue never drained");
        triggerExecutor.start(dispatched::add);
        return dispatched;
    }

    @Test
    public void test_a_failed_tombstone_force_deletes_nothing_and_fires_no_trigger() throws Exception {
        recorder.failingFor(Globals.TOMBSTONE_FILE_NAME + ".idx");

        final var response = deleteWhereNBelowThree();
        recorder.failingFor(null);

        assertEquals(ErrorCode.ERROR_DELETING.getCode(), response.getErrorCode());
        assertEquals(Set.of("c0", "c1", "c2", "c3", "c4", "c5"), stored(),
                "the tombstones were never durable, so not one delete may land");
        final var tombstones = fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(DOOMED.stream().noneMatch(tombstones::containsKey), String.valueOf(tombstones));
        verify(replicator, never()).broadcast(any());
        assertEquals(List.of(), awaitDispatches());
        assertEquals(List.of(), TriggerRunLog.pending());
    }

    @Test
    public void test_a_durable_conditional_delete_replicates_once_and_fires_per_document() throws Exception {
        final var response = deleteWhereNBelowThree();

        assertEquals(3L, assertInstanceOf(DeleteManyResponse.class, response).getDeletedCount());
        assertEquals(Set.of("c3", "c4", "c5"), stored());
        assertTrue(fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL).keySet().containsAll(DOOMED));
        final var payload = ArgumentCaptor.forClass(ReplicationPayload.class);
        verify(replicator).broadcast(payload.capture());
        assertEquals(ReplicationOp.DELETE, payload.getValue().getOp());
        assertEquals(DOOMED, new TreeSet<>(payload.getValue().getIds()));
        assertEquals(3, awaitDispatches().size());
    }
}
