package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ClusterWriteHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusteredDeleteStagingTest {
    private static final String DRAIN_MARKER = "__drain-marker__";
    private static final String ID = "present";
    private final Configuration config = Configuration.getInstance();
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private boolean origEnabled;
    private int origExpected;

    private static NodeInfo node() {
        return new NodeInfo("self", "127.0.0.1", 5000, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        TestUtils.setPrivateField(config, "triggersEnabled", true);
        TestUtils.setPrivateField(config, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(config, "triggerThreads", 1);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>(Map.of("self", node())));
        TestUtils.setPrivateField(membershipService, "self", node());
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(membershipService.membershipView());
        triggerExecutor.stop();
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("t", new LinkedHashSet<>(Set.of(EventType.DELETED)), "recalc",
                        TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner")));
    }

    @AfterEach
    public void tearDown() throws Exception {
        triggerExecutor.stop();
        for (final var run : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(run.getRunId(), run.getTriggerName());
        }
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.setPrivateField(config, "triggersEnabled", false);
        TestUtils.setPrivateField(config, "triggerThreads", 2);
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save() {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, ID);
        request.setObject(obj);
        request.set_id(ID);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private OperationStatus delete() {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(ID);
        return processor.processMessage(request).getStatus();
    }

    private OperationStatus deleteWithFailingReservation() {
        try (var writes = mockStatic(ClusterWriteHelper.class, CALLS_REAL_METHODS)) {
            writes.when(() -> ClusterWriteHelper.reserveDelete(anyString(), anyString(), any()))
                    .thenThrow(new IOException("disk full"));
            return delete();
        }
    }

    private List<TriggerEvent> dispatchedWhile(Runnable emit) throws InterruptedException {
        final var dispatched = new CopyOnWriteArrayList<TriggerEvent>();
        final var drained = new CountDownLatch(1);
        triggerExecutor.start(event -> {
            if (DRAIN_MARKER.equals(event.getTriggerName())) {
                drained.countDown();
            } else {
                dispatched.add(event);
            }
        });
        emit.run();
        triggerExecutor.submit(new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, DRAIN_MARKER,
                "recalc", false, List.of(), "owner", 0));
        if (!drained.await(5, TimeUnit.SECONDS)) {
            fail("the drain marker was never dispatched");
        }
        return List.copyOf(dispatched);
    }

    @Test
    public void test_a_failed_tombstone_reservation_leaves_no_staged_run() throws Exception {
        save();

        assertEquals(OperationStatus.ERROR, deleteWithFailingReservation());

        assertTrue(cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .anyMatch(pk -> ID.equals(pk.getValue())));
        assertTrue(TriggerRunLog.pending().isEmpty(),
                "a staged DELETED run outliving a delete that never applied fires on the next restart");
    }

    @Test
    public void test_a_retried_delete_fires_deleted_exactly_once() throws Exception {
        save();

        final var events = dispatchedWhile(() -> {
            assertEquals(OperationStatus.ERROR, deleteWithFailingReservation());
            assertEquals(OperationStatus.OK, delete());
        });

        assertEquals(1, events.size());
        assertEquals(EventType.DELETED, events.getFirst().getType());
        assertEquals(ID, events.getFirst().getEntries().getFirst().get_id());
        final var runIds = TriggerRunLog.pending().stream().map(AdminTriggerRunEntry::getRunId).distinct().toList();
        assertTrue(runIds.size() <= 1, "only the retry's own run may remain recorded, got " + runIds);
        runIds.forEach(runId -> assertEquals(events.getFirst().getRunId(), runId));
    }
}
