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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ClusterWriteHelper;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusteredDeleteTest {
    private static final String DRAIN_MARKER = "__drain-marker__";
    private static final String ID = "present";
    private final Configuration config = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private IntConsumer origHalt;
    private boolean deletedTriggerArmed;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cluster.enableAlone();
        origHalt = TestUtils.getPrivateField(faultInjector, "halt", IntConsumer.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (deletedTriggerArmed) {
            triggerExecutor.stop();
            for (final var run : TriggerRunLog.pending()) {
                TriggerDispatcher.consumeQuietly(run.getRunId(), run.getTriggerName());
            }
            cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
            TestUtils.setPrivateField(config, "triggersEnabled", false);
            TestUtils.setPrivateField(config, "triggerThreads", 2);
        }
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
        TestUtils.setPrivateField(faultInjector, "halt", origHalt);
        cluster.disable();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void armDeletedTrigger() throws Exception {
        deletedTriggerArmed = true;
        TestUtils.setPrivateField(config, "triggersEnabled", true);
        TestUtils.setPrivateField(config, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(config, "triggerThreads", 1);
        triggerExecutor.stop();
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("t", new LinkedHashSet<>(Set.of(EventType.DELETED)), "recalc",
                        TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner")));
    }

    private void save() {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, ID);
        request.setObject(obj);
        request.set_id(ID);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private OperationStatus delete(String id) {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    private OperationStatus deleteWithFailingReservation() {
        try (var writes = mockStatic(ClusterWriteHelper.class, CALLS_REAL_METHODS)) {
            writes.when(() -> ClusterWriteHelper.reserveDelete(anyString(), anyString(), any()))
                    .thenThrow(new IOException("disk full"));
            return delete(ID);
        }
    }

    private org.techhouse.ops.resp.OperationResponse deleteWhileOwnershipMovesAway() {
        final var elsewhere = ClusterTestHarness.node("elsewhere", 5001);
        try (var writes = mockStatic(ClusterWriteHelper.class, CALLS_REAL_METHODS)) {
            writes.when(() -> ClusterWriteHelper.reserveDelete(anyString(), anyString(), any())).thenAnswer(call -> {
                ownership.onMembershipChanged(new MembershipView(List.of(elsewhere)));
                return call.callRealMethod();
            });
            final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
            request.set_id(ID);
            return processor.processMessage(request);
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
        armDeletedTrigger();
        save();

        assertEquals(OperationStatus.ERROR, deleteWithFailingReservation());

        assertTrue(cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .anyMatch(pk -> ID.equals(pk.getValue())));
        assertTrue(TriggerRunLog.pending().isEmpty(),
                "a staged DELETED run outliving a delete that never applied fires on the next restart");
    }

    @Test
    public void test_a_retried_delete_fires_deleted_exactly_once() throws Exception {
        armDeletedTrigger();
        save();

        final var events = dispatchedWhile(() -> {
            assertEquals(OperationStatus.ERROR, deleteWithFailingReservation());
            assertEquals(OperationStatus.OK, delete(ID));
        });

        assertEquals(1, events.size());
        assertEquals(EventType.DELETED, events.getFirst().getType());
        assertEquals(ID, events.getFirst().getEntries().getFirst().get_id());
        final var runIds = TriggerRunLog.pending().stream().map(AdminTriggerRunEntry::getRunId).distinct().toList();
        assertTrue(runIds.size() <= 1, "only the retry's own run may remain recorded, got " + runIds);
        runIds.forEach(runId -> assertEquals(events.getFirst().getRunId(), runId));
    }

    @Test
    public void test_a_delete_whose_ownership_moves_before_its_reservation_is_refused_untouched() throws Exception {
        armDeletedTrigger();
        save();

        final var response = deleteWhileOwnershipMovesAway();

        assertEquals("421-1", response.getErrorCode());
        assertTrue(
                cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                        .anyMatch(pk -> ID.equals(pk.getValue())),
                "a delete with no tombstone would be undone by anti-entropy, so it must not apply locally");
        assertTrue(IocContainer.get(org.techhouse.fs.FileSystem.class).tombstones()
                .read(TestGlobals.DB, TestGlobals.COLL).isEmpty());
        assertTrue(TriggerRunLog.pending().isEmpty(), "a DELETED run for a delete that never applied must not stay");
    }

    @Test
    public void test_delete_of_a_missing_id_writes_no_tombstone() throws Exception {
        save();

        assertEquals(OperationStatus.NOT_FOUND, delete("never-existed"));

        assertTrue(fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void test_delete_of_a_present_id_still_writes_its_tombstone() throws Exception {
        save();

        assertEquals(OperationStatus.OK, delete(ID));

        final var tombstones = fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, tombstones.size());
        assertTrue(tombstones.containsKey("present"));
    }

    @Test
    public void test_a_failed_delete_retracts_its_reserved_tombstone() throws Exception {
        save();

        try (var deletes = org.mockito.Mockito.mockStatic(DeleteOperationHelper.class)) {
            deletes.when(() -> DeleteOperationHelper.executeDelete(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new java.io.IOException("disk full"));

            assertEquals(OperationStatus.ERROR, delete(ID));
        }

        assertTrue(fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL).isEmpty(),
                "a tombstone outliving a delete that never applied deletes the document cluster-wide on the next"
                        + " sweep");
    }

    @Test
    public void test_a_delete_that_answers_an_error_retracts_its_reserved_tombstone() throws Exception {
        save();

        try (var deletes = org.mockito.Mockito.mockStatic(DeleteOperationHelper.class)) {
            deletes.when(() -> DeleteOperationHelper.executeDelete(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new OperationResponse(OperationType.DELETE, ErrorCode.ERROR_DELETING));

            assertEquals(OperationStatus.ERROR, delete(ID));
        }

        assertTrue(fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    private boolean presentIsTombstonedAndStillLive() {
        try {
            return fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL).containsKey("present")
                    && cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                            .anyMatch(entry -> "present".equals(entry.getValue()));
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    public void test_the_tombstone_fault_point_fires_with_the_tombstone_on_disk_and_the_document_live()
            throws Exception {
        save();
        final var observed = new CopyOnWriteArrayList<Boolean>();
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        TestUtils.setPrivateField(faultInjector, "halt",
                (IntConsumer) _ -> observed.add(presentIsTombstonedAndStillLive()));
        faultInjector.arm(FaultPoint.DELETE_TOMBSTONE_RESERVED, FaultAction.HALT,
                Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(OperationStatus.OK, delete(ID));

        assertEquals(List.of(true), observed);
    }
}
