package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class GroupCommitSideEffectsTest {
    private static final String ACTOR = "alice";
    private static final String DRAIN_MARKER = "__drain-marker__";
    private static final String RUNS_WAL = "trigger_runs.wal";
    private static final long GROUP_BASE_FORCE = 1L;
    private static final long COLD_HOOK_TIMEOUT_MS = 30_000L;
    private static final String SUBJECT = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    private final Cache cache = IocContainer.get(Cache.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private final Configuration configuration = Configuration.getInstance();
    private final List<TriggerEvent> dispatched = new CopyOnWriteArrayList<>();
    private RecordingForcer recorder;
    private long hookTimeout;
    private GroupCommitFixture fixture;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        hookTimeout = configuration.getBeforeHookTimeoutMs();
        TestUtils.setPrivateField(configuration, "beforeHookTimeoutMs", COLD_HOOK_TIMEOUT_MS);
        triggerExecutor.stop();
        TriggerTestSupport.consumeAllPendingRuns();
        triggerExecutor.start(dispatched::add);
        recorder = RecordingForcer.install(true);
        fixture = new GroupCommitFixture(recorder);
        assertEquals(OperationStatus.OK, fixture.save("seed", "v0").getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
        recorder.close();
        triggerExecutor.stop();
        TriggerTestSupport.consumeAllPendingRuns();
        TriggerTestSupport.clearTriggersAndProcedures();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "beforeHookTimeoutMs", hookTimeout);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void install(String name, String timing, String source) throws Exception {
        final var procedure = ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, name, source), ACTOR);
        assertEquals(OperationStatus.OK, procedure.getStatus(), procedure.getMessage());
        final var existing = new ArrayList<>(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL));
        existing.add(new TriggerDefinition(name, new LinkedHashSet<>(Set.of(EventType.CREATED, EventType.UPDATED)),
                name, TriggerDefinition.MODE_DOCUMENT, timing, false, true, ACTOR, 1L, 1L, 1L, ACTOR));
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, existing);
    }

    private void installHook(String source) throws Exception {
        install("hook", TriggerDefinition.TIMING_BEFORE, source);
        final var warm = fixture.save("warm", "v");
        assertEquals(OperationStatus.OK, warm.getStatus(), warm.getMessage());
    }

    private void installAfterTrigger() throws Exception {
        install("audit", TriggerDefinition.TIMING_AFTER, "return 1;");
    }

    private static List<Supplier<OperationResponse>> saves(GroupCommitFixture fixture, String... ids) {
        final var writes = new ArrayList<Supplier<OperationResponse>>();
        for (final var id : ids) {
            writes.add(() -> fixture.save(id, "v-" + id));
        }
        return writes;
    }

    private Set<String> dispatchedIds() throws InterruptedException {
        final var drained = new CountDownLatch(1);
        triggerExecutor.stop();
        triggerExecutor.start(event -> {
            if (DRAIN_MARKER.equals(event.getTriggerName())) {
                drained.countDown();
            } else {
                dispatched.add(event);
            }
        });
        triggerExecutor.submit(new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, DRAIN_MARKER,
                "audit", false, List.of(), ACTOR, 0));
        assertTrue(drained.await(5, TimeUnit.SECONDS), "the drain marker was never dispatched");
        final var ids = new TreeSet<String>();
        for (final var event : dispatched) {
            event.getEntries().stream().map(DbEntry::get_id).forEach(ids::add);
        }
        return ids;
    }

    private static Set<String> pendingRunIds() throws Exception {
        final var ids = new TreeSet<String>();
        for (final var run : TriggerRunLog.pending()) {
            ids.addAll(run.getIds());
        }
        return ids;
    }

    private static String describe(List<OperationResponse> responses) {
        return responses.stream().map(response -> response.getErrorCode() + " " + response.getMessage()).toList()
                .toString();
    }

    private static boolean allOk(List<OperationResponse> responses) {
        return responses.stream().allMatch(response -> response.getStatus() == OperationStatus.OK);
    }

    @Test
    public void test_hooked_writes_share_one_force() throws Exception {
        installHook("export default (doc) => undefined;");

        final var responses = fixture.runQueued(saves(fixture, "a", "b", "c", "d", "seed"));

        assertTrue(allOk(responses), describe(responses));
        assertEquals(GROUP_BASE_FORCE + 1L, fixture.walForces());
        assertEquals("v-seed", fixture.valueOf("seed"));
    }

    @Test
    public void test_a_side_effecting_write_on_an_id_already_in_the_batch_ends_the_batch() throws Exception {
        installHook("export default (doc) => undefined;");

        final var responses = fixture.runQueued(List.of(() -> fixture.save("a", "first"), () -> fixture.save("b", "v"),
                () -> fixture.save("a", "second"), () -> fixture.save("c", "v")));

        assertTrue(allOk(responses), describe(responses));
        assertEquals(GROUP_BASE_FORCE + 2L, fixture.walForces(), "the second write on a starts the next batch");
        assertEquals("second", fixture.valueOf("a"));
    }

    @Test
    public void test_a_plain_write_after_a_side_effecting_one_on_the_same_id_ends_the_batch() throws Exception {
        installAfterTrigger();

        final var responses = fixture.runQueued(
                List.of(() -> fixture.save("a", "kept"), () -> fixture.delete("a"), () -> fixture.save("b", "v")));

        assertTrue(allOk(responses), describe(responses));
        assertNull(fixture.valueOf("a"));
        assertTrue(dispatchedIds().contains("a"), "the update of a fired before its delete landed");
    }

    @Test
    public void test_a_write_refused_by_its_hook_discards_only_its_runs() throws Exception {
        installHook("export default (doc) => doc.v === 'v-refused' ? false : undefined;");
        installAfterTrigger();

        final var responses = fixture.runQueued(saves(fixture, "a", "refused", "b"));

        assertEquals(OperationStatus.OK, responses.get(0).getStatus());
        assertNotEquals(OperationStatus.OK, responses.get(1).getStatus());
        assertEquals(OperationStatus.OK, responses.get(2).getStatus());
        assertEquals(Set.of("a", "b"), pendingRunIds());
        assertEquals(Set.of("a", "b"), dispatchedIds());
    }

    @Test
    public void test_triggered_writes_share_one_wal_force_and_one_trigger_runs_force() throws Exception {
        installAfterTrigger();
        assertEquals(OperationStatus.OK, fixture.save("warm", "v").getStatus());

        final var responses = fixture.runQueued(saves(fixture, "a", "b", "c", "d", "e", "f"));

        assertTrue(allOk(responses), describe(responses));
        assertEquals(GROUP_BASE_FORCE + 1L, fixture.walForces());
        assertEquals(1L, fixture.forcesOf(RUNS_WAL), "every staged run of the batch rides one record");
        assertTrue(dispatchedIds().containsAll(Set.of("a", "b", "c", "d", "e", "f")));
    }

    @Test
    public void test_runs_are_submitted_only_after_the_force() throws Exception {
        installAfterTrigger();
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        faultInjector.arm(FaultPoint.WAL_GROUP_APPENDED, FaultAction.PAUSE, SUBJECT);

        final var firedBefore = triggerExecutor.getFired();
        final var group = fixture.runQueuedInBackground(saves(fixture, "a", "b", "c"));
        assertTrue(faultInjector.awaitReached(FaultPoint.WAL_GROUP_APPENDED, GroupCommitFixture.WAIT_MS));
        final var firedWhilePaused = triggerExecutor.getFired() - firedBefore + dispatched.size()
                + triggerExecutor.getQueued();
        assertEquals(Set.of("a", "b", "c"), pendingRunIds(), "the runs are staged before the force");
        faultInjector.release(FaultPoint.WAL_GROUP_APPENDED);

        assertTrue(allOk(group.get(GroupCommitFixture.WAIT_MS, TimeUnit.MILLISECONDS)));
        assertEquals(0L, firedWhilePaused);
        assertEquals(Set.of("a", "b", "c"), dispatchedIds());
    }

    @Test
    public void test_a_taken_back_group_leaves_no_staged_runs() throws Exception {
        installAfterTrigger();
        assertTrue(allOk(fixture.runQueued(saves(fixture, "base1", "base2"))), "logs the group base");
        TriggerTestSupport.consumeAllPendingRuns();
        dispatched.clear();
        recorder.failingFor(GroupCommitFixture.WAL);

        final var responses = fixture.runQueued(saves(fixture, "a", "b", "seed"));
        recorder.failingFor(null);

        assertTrue(responses.stream()
                .allMatch(response -> ErrorCode.ERROR_SAVING.getCode().equals(response.getErrorCode())));
        assertEquals(Set.of(), pendingRunIds());
        assertEquals(Set.of(), dispatchedIds());
        assertEquals("v0", fixture.valueOf("seed"));
    }
}
