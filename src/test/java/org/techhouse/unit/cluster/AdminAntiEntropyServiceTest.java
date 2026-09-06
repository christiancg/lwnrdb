package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.AntiEntropyService;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.config.Configuration;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestUtils;

public class AdminAntiEntropyServiceTest {
    private final AdminAntiEntropyService service = IocContainer.get(AdminAntiEntropyService.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Configuration config = Configuration.getInstance();
    private PeerConnectionPool realPool;
    private PeerConnectionPool realDocPool;
    private PeerConnectionPool mockPool;
    private boolean origEnabled;
    private long origInterval;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        origEnabled = config.isClusterEnabled();
        origInterval = config.getAntiEntropyIntervalMs();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        realPool = TestUtils.getPrivateField(service, "pool", PeerConnectionPool.class);
        realDocPool = TestUtils.getPrivateField(antiEntropyService, "pool", PeerConnectionPool.class);
        mockPool = mock(PeerConnectionPool.class);
        TestUtils.setPrivateField(service, "pool", mockPool);
        TestUtils.setPrivateField(antiEntropyService, "pool", mockPool);
        TestUtils.setPrivateField(service, "started", true);
        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(false));
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        final var self = node("self", 19990);
        final var peer = node("peer", 19991);
        members.put(self.getNodeId(), self);
        members.put(peer.getNodeId(), peer);
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(service, "pool", realPool);
        TestUtils.setPrivateField(antiEntropyService, "pool", realDocPool);
        TestUtils.setPrivateField(service, "started", false);
        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(false));
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "antiEntropyIntervalMs", origInterval);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void stubSnapshot(List<JsonObject> dbs, List<JsonObject> colls, List<JsonObject> users) throws Exception {
        stubSnapshot(dbs, colls, users, new JsonObject());
    }

    private void stubSnapshot(List<JsonObject> dbs, List<JsonObject> colls, List<JsonObject> users, JsonObject schemas)
            throws Exception {
        final var ack = new ClusterMessage();
        ack.setType(ClusterMessageType.ADMIN_SNAPSHOT_ACK);
        ack.setAdminSnapshot(new AdminSnapshotPayload(5L, dbs, colls, users, schemas));
        when(mockPool.request(any(), any(), anyLong())).thenReturn(ack);
    }

    private static JsonObject dbJson(String name, List<String> owners) {
        return new AdminDbEntry(name, new ArrayList<>(), new ArrayList<>(owners)).getData();
    }

    private static JsonObject collJson(String db, String coll, Set<String> indexes) {
        final var json = new AdminCollEntry(db, coll, new java.util.HashSet<>(indexes)).getData().deepCopy();
        json.addProperty(org.techhouse.config.Globals.PK_FIELD, Cache.getCollectionIdentifier(db, coll));
        return json;
    }

    private static JsonObject userJson() {
        return new AdminUserEntry("alice", "hash-" + "alice", false, Set.of(), Map.of(), Map.of()).getData();
    }

    @Test
    public void test_conform_creates_missing_database_and_collection() throws Exception {
        stubSnapshot(List.of(dbJson("newdb", List.of("alice"))), List.of(collJson("newdb", "newcoll", Set.of())),
                List.of());

        service.reconcile();

        final var dbEntry = cache.getAdminDbEntry("newdb");
        assertNotNull(dbEntry);
        assertTrue(dbEntry.getOwners().contains("alice"));
        assertNotNull(cache.getAdminCollectionEntry("newdb", "newcoll"));
        assertTrue(cache.getCollectionNamesForDatabase("newdb").contains("newcoll"));
    }

    @Test
    public void test_conform_creates_missing_index_and_drops_extra_index() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        IndexHelper.createIndex(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL, "stale");
        AdminOperationHelper.saveNewIndex(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL,
                "stale");

        stubSnapshot(List.of(dbJson(org.techhouse.test.TestGlobals.DB, List.of())), List
                .of(collJson(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL, Set.of("wanted"))),
                List.of());

        service.reconcile();

        final var indexes = cache.getIndexesForCollection(org.techhouse.test.TestGlobals.DB,
                org.techhouse.test.TestGlobals.COLL);
        assertTrue(indexes.contains("wanted"));
        assertFalse(indexes.contains("stale"));
    }

    @Test
    public void test_conform_installs_schema_from_snapshot() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        final var schema = new JsonObject();
        schema.add("type", new org.techhouse.ejson.elements.JsonString("object"));
        final var schemas = new JsonObject();
        schemas.add(
                Cache.getCollectionIdentifier(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL),
                schema);
        stubSnapshot(List.of(dbJson(org.techhouse.test.TestGlobals.DB, List.of())),
                List.of(collJson(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL, Set.of())),
                List.of(), schemas);

        service.reconcile();

        assertEquals(schema,
                cache.getCollectionSchema(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL));
    }

    @Test
    public void test_conform_removes_schema_absent_from_snapshot() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        final var schema = new JsonObject();
        schema.add("type", new org.techhouse.ejson.elements.JsonString("object"));
        // Written to disk as SAVE_SCHEMA does, not just cached: conform reads the authoritative file, so a
        // cache-only schema is a state the server never produces.
        IocContainer.get(org.techhouse.fs.FileSystem.class).writeCollectionSchema(org.techhouse.test.TestGlobals.DB,
                org.techhouse.test.TestGlobals.COLL, IocContainer.get(org.techhouse.ejson.EJson.class).toJson(schema));
        cache.putCollectionSchema(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL, schema);

        stubSnapshot(List.of(dbJson(org.techhouse.test.TestGlobals.DB, List.of())),
                List.of(collJson(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL, Set.of())),
                List.of());

        service.reconcile();

        assertNull(cache.getCollectionSchema(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL));
    }

    @Test
    public void test_conform_reconciles_database_owners() throws Exception {
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry("ownersdb", new ArrayList<>(), List.of("old")));

        stubSnapshot(List.of(dbJson("ownersdb", List.of("new1", "new2"))), List.of(), List.of());

        service.reconcile();

        final var owners = cache.getAdminDbEntry("ownersdb").getOwners();
        assertTrue(owners.contains("new1"));
        assertTrue(owners.contains("new2"));
        assertFalse(owners.contains("old"));
    }

    @Test
    public void test_conform_drops_local_collection_and_database_absent_from_snapshot() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry("keepdb", new ArrayList<>(), List.of()));

        stubSnapshot(List.of(dbJson("keepdb", List.of())), List.of(), List.of());

        service.reconcile();

        assertNull(cache.getAdminDbEntry(org.techhouse.test.TestGlobals.DB));
        assertNotNull(cache.getAdminDbEntry("keepdb"));
    }

    @Test
    public void test_conform_upserts_snapshot_user_and_deletes_absent_user() throws Exception {
        AdminOperationHelper.saveUserEntry(new AdminUserEntry("stale", "h", false, Set.of(), Map.of(), Map.of()));

        stubSnapshot(List.of(), List.of(), List.of(userJson()));

        service.reconcile();

        assertNotNull(cache.getAdminUserEntry("alice"));
        assertNull(cache.getAdminUserEntry("stale"));
    }

    @Test
    public void test_reconcile_skips_conform_when_local_epoch_at_least_all_peers() throws Exception {
        TestUtils.setPrivateField(adminEpoch, "epoch", 10L);
        stubSnapshot(List.of(dbJson("newdb", List.of())), List.of(), List.of());

        service.reconcile();

        assertNull(cache.getAdminDbEntry("newdb"));
        assertTrue(service.hasCompletedAdminSync());
    }

    @Test
    public void test_reconcile_sets_sync_completed_and_skips_unreachable_peer() throws Exception {
        when(mockPool.request(any(), any(), anyLong())).thenThrow(new RuntimeException("unreachable"));

        service.reconcile();

        assertNull(cache.getAdminDbEntry("newdb"));
        assertTrue(service.hasCompletedAdminSync());
    }

    // The gate is gossiped so peers can keep a script off a node that is not caught up yet.
    @Test
    public void test_start_marks_this_node_as_admin_syncing_and_stop_clears_it() throws Exception {
        TestUtils.setPrivateField(config, "antiEntropyIntervalMs", 0L);
        TestUtils.setPrivateField(service, "started", false);
        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(false));

        service.start();
        assertTrue(syncingFlag());

        service.stop();
        assertFalse(syncingFlag());
    }

    @Test
    public void test_reconcile_publishes_the_caught_up_state_to_membership() throws Exception {
        membershipService.setAdminSyncing(true);
        when(mockPool.request(any(), any(), anyLong())).thenThrow(new RuntimeException("unreachable"));

        service.reconcile();

        assertTrue(service.hasCompletedAdminSync());
        assertFalse(syncingFlag());
    }

    private boolean syncingFlag() throws Exception {
        return TestUtils.getPrivateField(membershipService, "adminSyncing", Boolean.class);
    }

    @Test
    public void test_reconcile_is_noop_when_clustering_disabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        stubSnapshot(List.of(dbJson("newdb", List.of())), List.of(), List.of());

        service.reconcile();

        assertNull(cache.getAdminDbEntry("newdb"));
        assertFalse(service.hasCompletedAdminSync());
    }

    @Test
    public void test_reconcile_skips_peer_returning_error() throws Exception {
        final var error = new ClusterMessage();
        error.setType(ClusterMessageType.ERROR);
        error.setErrorMessage("boom");
        when(mockPool.request(any(), any(), anyLong())).thenReturn(error);

        service.reconcile();

        assertNull(cache.getAdminDbEntry("newdb"));
        assertTrue(service.hasCompletedAdminSync());
    }

    @Test
    public void test_conform_drops_orphan_collection_in_kept_database() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();

        stubSnapshot(List.of(dbJson(org.techhouse.test.TestGlobals.DB, List.of())),
                List.of(collJson(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL, Set.of())),
                List.of());

        service.reconcile();

        assertNotNull(
                cache.getAdminCollectionEntry(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL));
        assertNull(cache.getAdminCollectionEntry(org.techhouse.test.TestGlobals.DB,
                org.techhouse.test.TestGlobals.JOIN_COLL));
    }

    @Test
    public void test_build_snapshot_includes_databases_collections_and_users() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveUserEntry(new AdminUserEntry("snapuser", "h", false, Set.of(), Map.of(), Map.of()));
        TestUtils.setPrivateField(adminEpoch, "epoch", 2L);

        final var snapshot = service.buildSnapshot();

        assertEquals(2L, snapshot.getEpoch());
        assertTrue(snapshot.getDatabases().stream().anyMatch(db -> org.techhouse.test.TestGlobals.DB
                .equals(db.get(org.techhouse.config.Globals.PK_FIELD).asJsonString().getValue())));
        assertTrue(snapshot.getCollections().stream()
                .anyMatch(coll -> Cache
                        .getCollectionIdentifier(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL)
                        .equals(coll.get(org.techhouse.config.Globals.PK_FIELD).asJsonString().getValue())));
        assertTrue(snapshot.getUsers().stream().anyMatch(
                user -> "snapuser".equals(user.get(org.techhouse.config.Globals.PK_FIELD).asJsonString().getValue())));
    }

    @Test
    public void test_start_membership_trigger_and_stop() throws Exception {
        TestUtils.setPrivateField(config, "antiEntropyIntervalMs", 3600000L);
        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(false));
        service.start();
        try {
            service.onMembershipChanged(membershipService.membershipView());
            // The reconcile runs on the service's single-thread executor; draining it with a barrier task
            // (FIFO) deterministically waits for that reconcile to finish without busy-waiting.
            final var executor = TestUtils.getPrivateField(service, "reconcileExecutor", ExecutorService.class);
            executor.submit(() -> null).get(3, TimeUnit.SECONDS);
            assertTrue(service.hasCompletedAdminSync());
        } finally {
            service.stop();
        }
    }
}
