package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorClusterWriteTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final Configuration config = Configuration.getInstance();
    private boolean origEnabled;
    private int origExpected;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    private static SaveRequest save(String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        request.setObject(object);
        request.set_id(id);
        return request;
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void configureMembership(int expectedSize, NodeInfo self, NodeInfo... peers) throws Exception {
        TestUtils.setPrivateField(config, "clusterExpectedSize", expectedSize);
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        members.put(self.getNodeId(), self);
        for (final var peer : peers) {
            members.put(peer.getNodeId(), peer);
        }
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
        ownership.setSelfNodeId(self.getNodeId());
        ownership.onMembershipChanged(membershipService.membershipView());
    }

    @Test
    public void test_single_node_owner_save_succeeds() throws Exception {
        configureMembership(1, node("self", 9990));
        final var response = processor.processMessage(save("c1"));
        assertInstanceOf(SaveResponse.class, response);
        assertEquals(OperationStatus.OK, response.getStatus());
    }

    @Test
    public void test_owner_bulk_save_and_delete_succeed() throws Exception {
        configureMembership(1, node("self", 9990));
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString("c2"));
        bulk.setObjects(List.of(object));
        assertInstanceOf(BulkSaveResponse.class, processor.processMessage(bulk));

        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("c2");
        assertInstanceOf(DeleteResponse.class, processor.processMessage(delete));
    }

    @Test
    public void test_save_rejected_without_quorum() throws Exception {
        configureMembership(3, node("self", 9990));
        final var response = processor.processMessage(save("c3"));
        assertEquals("503-2", response.getErrorCode());
    }

    @Test
    public void test_save_rejected_when_not_owner() throws Exception {
        configureMembership(2, node("self", 9990), node("other", 9991));
        String ownedByOther = null;
        for (var i = 0; i < 500 && ownedByOther == null; i++) {
            if (!ownership.isOwner(TestGlobals.DB, "coll-" + i)) {
                ownedByOther = "coll-" + i;
            }
        }
        final var request = new SaveRequest(TestGlobals.DB, ownedByOther);
        final var object = new JsonObject();
        object.add("_id", new JsonString("c4"));
        request.setObject(object);
        request.set_id("c4");
        final var response = processor.processMessage(request);
        assertEquals("421-1", response.getErrorCode());
    }
}
