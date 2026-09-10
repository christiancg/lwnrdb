package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static org.techhouse.test.ClusterTestHarness.SECRET;
import static org.techhouse.test.ClusterTestHarness.node;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.operators.ScriptOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterRouterIntegrationTest {
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cluster.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private String collectionOwnedByOther() {
        for (var i = 0; i < 500; i++) {
            final var coll = "routed-" + i;
            if (!ownership.isOwner(TestGlobals.DB, coll)) {
                return coll;
            }
        }
        throw new IllegalStateException("no collection owned by the other node");
    }

    private void createCollection(String coll) throws Exception {
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, coll));
        fs.createCollectionFile(TestGlobals.DB, coll);
    }

    private String rawFind(String coll, String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, coll);
        request.set_id(id);
        return eJson.toJson(request);
    }

    private String rawSave(String coll, String id) {
        final var request = new SaveRequest(TestGlobals.DB, coll);
        request.setObject(doc(id));
        request.set_id(id);
        return eJson.toJson(request);
    }

    // The forward ships the caller's own pipeline JSON, which is what lets a polymorphic operator - a
    // SCRIPT one included - survive the trip and be re-parsed on the owner.
    @Test
    public void test_forwarded_aggregate_runs_the_script_on_the_owner() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        final var coll = collectionOwnedByOther();
        createCollection(coll);
        ReplicatedApplyHelper.apply(
                new ReplicationPayload(TestGlobals.DB, coll, ReplicationOp.UPSERT, List.of(priced("s1", 5)), null));
        ReplicatedApplyHelper.apply(
                new ReplicationPayload(TestGlobals.DB, coll, ReplicationOp.UPSERT, List.of(priced("s2", 50)), null));

        final var request = new AggregateRequest(TestGlobals.DB, coll);
        request.setAggregationSteps(
                List.of(new FilterAggregationStep(new ScriptOperator("export default (doc) => doc.price > 10;"))));
        final var raw = "{\"type\":\"AGGREGATE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + coll + "\",\"aggregationSteps\":[{\"type\":\"FILTER\",\"operator\":"
                + "{\"script\":\"export default (doc) => doc.price > 10;\"}}]}";
        final var relayed = router.forward(request, raw, false, null, null);

        assertNotNull(relayed);
        assertTrue(relayed.contains("s2"), "expected the owner's scripted result, got: " + relayed);
        assertFalse(relayed.contains("\"s1\""), "the script predicate must have excluded s1, got: " + relayed);
    }

    private static JsonObject priced(String id, double price) {
        final var object = doc(id);
        object.add("price", new org.techhouse.ejson.elements.JsonNumber(price));
        return object;
    }

    @Test
    public void test_router_forwards_read_to_owner() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        final var coll = collectionOwnedByOther();
        createCollection(coll);
        ReplicatedApplyHelper
                .apply(new ReplicationPayload(TestGlobals.DB, coll, ReplicationOp.UPSERT, List.of(doc("f1")), null));

        final var request = new FindByIdRequest(TestGlobals.DB, coll);
        request.set_id("f1");
        final var relayed = router.forward(request, rawFind(coll, "f1"), false, null, null);

        assertNotNull(relayed);
        assertTrue(relayed.contains("f1"), "expected forwarded read response to contain the document, got: " + relayed);
    }

    @Test
    public void test_router_executes_locally_when_self_owns() throws Exception {
        cluster.configureMembership(1, node("self", cluster.serverPort()));
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(doc("local"));
        assertNull(router.forward(request, rawSave(TestGlobals.COLL, "local"), false, null, null));
    }

    @Test
    public void test_read_falls_back_to_local_when_owner_unreachable() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("other", 1));
        final var coll = collectionOwnedByOther();
        final var request = new FindByIdRequest(TestGlobals.DB, coll);
        request.set_id("x");
        assertNull(router.forward(request, rawFind(coll, "x"), false, null, null));
    }

    @Test
    public void test_write_errors_when_owner_unreachable() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("other", 1));
        final var coll = collectionOwnedByOther();
        final var request = new SaveRequest(TestGlobals.DB, coll);
        request.setObject(doc("y"));
        final var relayed = router.forward(request, rawSave(coll, "y"), false, null, null);
        assertNotNull(relayed);
        assertTrue(relayed.contains("503-4"), "expected OWNER_UNREACHABLE, got: " + relayed);
    }

    @Test
    public void test_forward_request_handler_executes_write_on_owner() throws Exception {
        cluster.configureMembership(1, node("self", cluster.serverPort()));
        final var message = new ClusterMessage(null, ClusterMessageType.FORWARD_REQUEST, SECRET, null, null);
        message.setForwardBody(ForwardBody.encode(rawSave(TestGlobals.COLL, "h1")));
        final var response = pool.request(cluster.serverAddress(), message, 3000);

        assertEquals(ClusterMessageType.FORWARD_RESPONSE, response.getType());
        assertTrue(ForwardBody.decode(response.getForwardBody()).contains("h1"));
        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("h1");
        assertEquals(OperationStatus.OK, processor.processMessage(find).getStatus());
    }
}
