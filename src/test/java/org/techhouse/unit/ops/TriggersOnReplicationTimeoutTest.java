package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggersOnReplicationTimeoutTest {
    private final Configuration config = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final CopyOnWriteArrayList<TriggerEvent> captured = new CopyOnWriteArrayList<>();
    private boolean origEnabled;
    private boolean origTriggers;
    private int origExpected;
    private long origMaxEntrySize;
    private Replicator origReplicator;

    private static NodeInfo node() {
        return new NodeInfo("self", "127.0.0.1", 5000, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        origTriggers = config.isTriggersEnabled();
        origExpected = config.getClusterExpectedSize();
        origMaxEntrySize = config.getMaxEntrySize();
        origReplicator = TestUtils.getPrivateField(coordinator, "replicator", Replicator.class);
        TestUtils.setPrivateField(config, "triggersEnabled", true);
        captured.clear();
        triggerExecutor.stop();
        triggerExecutor.start(captured::add);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit",
                        new LinkedHashSet<>(Set.of(EventType.CREATED, EventType.UPDATED, EventType.DELETED)), "recalc",
                        TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner")));
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        TestUtils.setPrivateField(membershipService, "members",
                new ConcurrentHashMap<>(java.util.Map.of("self", node())));
        TestUtils.setPrivateField(membershipService, "self", node());
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(membershipService.membershipView());
        final var replicator = mock(Replicator.class);
        when(replicator.broadcast(any())).thenReturn(ReplicationOutcome.TIMEOUT);
        TestUtils.setPrivateField(coordinator, "replicator", replicator);
    }

    @AfterEach
    public void tearDown() throws Exception {
        triggerExecutor.stop();
        TestUtils.setPrivateField(coordinator, "replicator", origReplicator);
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "triggersEnabled", origTriggers);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        TestUtils.setPrivateField(config, "maxEntrySize", origMaxEntrySize);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    private org.techhouse.ops.resp.OperationResponse save(String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id));
        request.set_id(id);
        return processor.processMessage(request);
    }

    private List<TriggerEvent> settle() {
        for (var i = 0; i < 100 && captured.isEmpty(); i++) {
            sleep(5);
        }
        sleep(30);
        return List.copyOf(captured);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void test_triggers_fire_when_replication_times_out() {
        final var response = save("t1");

        assertEquals("503-3", response.getErrorCode(), "the client is told the write did not replicate");

        final var events = settle();
        assertEquals(1, events.size(), "the local commit stands, so its trigger must still run: " + events);
        assertEquals(EventType.CREATED, events.getFirst().getType());
    }

    @Test
    public void test_bulk_save_triggers_fire_when_replication_times_out() {
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(document("b1"), document("b2")));

        final var response = processor.processMessage(request);

        assertEquals("503-3", response.getErrorCode());
        assertEquals(2, settle().size());
    }

    @Test
    public void test_delete_triggers_fire_when_replication_times_out() {
        save("d1");
        settle();
        captured.clear();
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("d1");

        final var response = processor.processMessage(request);

        assertEquals("503-3", response.getErrorCode());
        final var events = settle();
        assertEquals(1, events.size(), "a committed delete must still fire its trigger: " + events);
        assertEquals(EventType.DELETED, events.getFirst().getType());
    }

    @Test
    public void test_triggers_do_not_fire_when_the_local_write_failed() throws Exception {
        TestUtils.setPrivateField(config, "maxEntrySize", 1L);

        final var response = save("too-big");

        assertNotEquals(org.techhouse.ops.OperationStatus.OK, response.getStatus(),
                "the oversized write must be refused, got " + response.getErrorCode());
        sleep(100);
        assertTrue(captured.isEmpty(), "a write that never committed must fire nothing: " + captured);
    }
}
