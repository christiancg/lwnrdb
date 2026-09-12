package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ScriptPlacement;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

abstract class ScriptClusterTestBase {
    static final String ADMIN = "scriptrouteadmin";
    private static final int SCRIPT_CAPACITY = 10;
    private static final int PEER_LOAD = 2;
    final ClusterTestHarness cluster = new ClusterTestHarness();
    final Configuration config = Configuration.getInstance();
    final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    final FileSystem fs = IocContainer.get(FileSystem.class);
    final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    final ScriptPlacement scriptPlacement = IocContainer.get(ScriptPlacement.class);
    boolean origScripts;
    boolean origScriptRouting;
    int origLocalityWeight;

    static NodeInfo node(String id, int port) {
        return ClusterTestHarness.node(id, port);
    }

    static NodeInfo node(String id, int port, int scriptLoad) {
        final var node = new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L, scriptLoad);
        node.setAdminEpoch(IocContainer.get(AdminEpoch.class).current());
        return node;
    }

    // The cap is arbitrary and shared by both sides of the pair; only the ratio between them decides.
    static NodeInfo cappedNode(String id, int port, int scriptLoad) {
        final var node = new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L, scriptLoad, SCRIPT_CAPACITY);
        node.setAdminEpoch(IocContainer.get(AdminEpoch.class).current());
        return node;
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        createAdminUser();
        origScripts = config.isScriptsEnabled();
        origScriptRouting = config.isScriptRoutingEnabled();
        origLocalityWeight = config.getScriptLocalityWeight();
        cluster.start(false, 1000L);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "scriptsEnabled", origScripts);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", origScriptRouting);
        TestUtils.setPrivateField(config, "scriptLocalityWeight", origLocalityWeight);
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    static void createAdminUser() {
        final var request = new CreateUserRequest();
        request.setUsername(ADMIN);
        request.setPassword("password123");
        request.setAdmin(true);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("value", new JsonString("hello"));
        return object;
    }

    void configureMembership(int expectedSize, NodeInfo self, NodeInfo... others) throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        cluster.configureMembership(expectedSize, self, others);
    }

    String collectionOwnedByOther() {
        for (var i = 0; i < 500; i++) {
            final var coll = "script-routed-" + i;
            if (!ownership.isOwner(TestGlobals.DB, coll)) {
                return coll;
            }
        }
        throw new IllegalStateException("no collection owned by the other node");
    }

    void createCollection(String coll) throws Exception {
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, coll));
        fs.createCollectionFile(TestGlobals.DB, coll);
    }

    OperationStatus findStatus(String coll, String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, coll);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    // Self carries a load the peer does not, so placement always prefers the peer. Locality is pinned off
    // so these cases keep exercising the load path in isolation.
    void enableScriptRouting() throws Exception {
        configureMembership(2, node("self", 19990, 9), node("other", cluster.serverPort(), 0));
        TestUtils.setPrivateField(config, "scriptsEnabled", true);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", true);
        TestUtils.setPrivateField(config, "scriptLocalityWeight", 0);
    }

    // The peer carries the higher load, so load alone keeps the run here - no tie, and so no dependence
    // on how an undecided pair is resolved.
    void configureMembershipWithAPeerOwningTheWholeDatabase() throws Exception {
        final var collections = IocContainer.get(Cache.class).getCollectionNamesForDatabase(TestGlobals.DB);
        assertFalse(collections.isEmpty(), "the fixture database must have at least one collection");
        for (var i = 0; i < 500; i++) {
            final var peer = cappedNode("z-owner-" + i, cluster.serverPort(), PEER_LOAD);
            configureMembership(2, cappedNode("self", 19990, 0), peer);
            if (collections.stream()
                    .allMatch(coll -> peer.getNodeId().equals(ownership.ownerFor(TestGlobals.DB, coll)))) {
                return;
            }
        }
        throw new IllegalStateException("no peer id owning every collection of the database");
    }
}
