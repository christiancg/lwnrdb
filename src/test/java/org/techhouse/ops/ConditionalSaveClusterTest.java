package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveWhereResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ConditionalSaveClusterTest {
    private static final Set<String> MATCHED = Set.of("u0", "u1", "u2");
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Configuration config = Configuration.getInstance();
    private final Replicator replicator = mock(Replicator.class);
    private Replicator originalReplicator;
    private boolean originalEnabled;
    private int originalExpected;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalEnabled = config.isClusterEnabled();
        originalExpected = config.getClusterExpectedSize();
        originalReplicator = TestUtils.getPrivateField(coordinator, "replicator", Replicator.class);
        TestUtils.setPrivateField(coordinator, "replicator", replicator);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        final var self = new NodeInfo("self", "127.0.0.1", 9990, NodeState.ALIVE, 1L, 1L);
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        members.put(self.getNodeId(), self);
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
        ownership.setSelfNodeId(self.getNodeId());
        ownership.onMembershipChanged(membershipService.membershipView());
        when(replicator.broadcast(any())).thenReturn(ReplicationOutcome.QUORUM_MET);
        final var seed = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var documents = new ArrayList<JsonObject>();
        for (var i = 0; i < 6; i++) {
            final var document = new JsonObject();
            document.add(Globals.PK_FIELD, new JsonString("u" + i));
            document.add("n", new JsonNumber(i));
            documents.add(document);
        }
        seed.setObjects(documents);
        assertEquals(OperationStatus.OK, processor.processMessage(seed).getStatus());
        clearInvocations(replicator);
    }

    @AfterEach
    public void tearDown() throws Exception {
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

    private OperationResponse saveWhereNBelowThree() {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setCondition(new FieldOperator(FieldOperatorType.SMALLER_THAN, "n", new JsonNumber(3)));
        final var patch = new JsonObject();
        patch.add("status", new JsonString("replicated"));
        request.setObject(patch);
        return processor.processMessage(request);
    }

    @Test
    public void test_a_conditional_save_replicates_every_updated_id_in_one_upsert() {
        final var response = saveWhereNBelowThree();

        assertEquals(3L, assertInstanceOf(SaveWhereResponse.class, response).getUpdatedCount());
        final var payload = ArgumentCaptor.forClass(ReplicationPayload.class);
        verify(replicator).broadcast(payload.capture());
        assertEquals(ReplicationOp.UPSERT, payload.getValue().getOp());
        final var replicated = new TreeSet<String>();
        payload.getValue().getDocuments()
                .forEach(document -> replicated.add(document.get(Globals.PK_FIELD).asJsonString().getValue()));
        assertEquals(MATCHED, replicated);
    }

    @Test
    public void test_a_replication_timeout_is_answered_as_a_save() {
        when(replicator.broadcast(any())).thenReturn(ReplicationOutcome.TIMEOUT);

        final var response = saveWhereNBelowThree();

        assertEquals(ErrorCode.REPLICATION_TIMEOUT.getCode(), response.getErrorCode());
        assertEquals(OperationType.SAVE, response.getType());
    }

    @Test
    public void test_a_conditional_save_matching_nothing_replicates_nothing() {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setCondition(new FieldOperator(FieldOperatorType.SMALLER_THAN, "n", new JsonNumber(-1)));
        final var patch = new JsonObject();
        patch.add("status", new JsonString("never"));
        request.setObject(patch);

        assertEquals(0L,
                assertInstanceOf(SaveWhereResponse.class, processor.processMessage(request)).getUpdatedCount());
        verify(replicator, never()).broadcast(any());
        assertEquals(6, cache.getWholeCollection(TestGlobals.DB, TestGlobals.COLL).size());
    }
}
