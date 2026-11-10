package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class GroupedBatchCheckpointTest {
    private static final String ACTOR = "alice";
    private static final String WAL = GroupCommitFixture.WAL;
    private static final String REWRITTEN_WAL = WAL + ".repair";
    private static final long CHECKPOINT_BYTES = 1500L;
    private static final int LARGE_VALUE_CHARS = 4000;
    private final Cache cache = IocContainer.get(Cache.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final Configuration configuration = Configuration.getInstance();
    private final List<TriggerEvent> dispatched = new CopyOnWriteArrayList<>();
    private final long shippedCheckpointBytes = configuration.getWalCheckpointBytes();
    private RecordingForcer recorder;
    private GroupCommitFixture fixture;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        triggerExecutor.stop();
        TriggerTestSupport.consumeAllPendingRuns();
        triggerExecutor.start(dispatched::add);
        recorder = RecordingForcer.install(true);
        fixture = new GroupCommitFixture(recorder);
        assertEquals(OperationStatus.OK, fixture.save("seed", "v0").getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "walCheckpointBytes", shippedCheckpointBytes);
        recorder.close();
        triggerExecutor.stop();
        TriggerTestSupport.consumeAllPendingRuns();
        TriggerTestSupport.clearTriggersAndProcedures();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void installAfterTrigger() throws Exception {
        final var procedure = ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "audit", "return 1;"), ACTOR);
        assertEquals(OperationStatus.OK, procedure.getStatus(), procedure.getMessage());
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "audit",
                        TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_AFTER, false, true, ACTOR, 1L, 1L, 1L,
                        ACTOR)));
    }

    private void refuseTheGroupForceAfterTheCheckpoint() {
        recorder.refusingWhen((forced, name) -> {
            final var rewritten = forced.lastIndexOf(REWRITTEN_WAL);
            return WAL.equals(name) && rewritten >= 0
                    && forced.subList(rewritten, forced.size()).stream().filter(WAL::equals).count() == 1L;
        });
    }

    private Set<String> dispatchedIds() {
        assertTrue(triggerExecutor.drain(GroupCommitFixture.WAIT_MS), "the trigger queue never drained");
        triggerExecutor.start(dispatched::add);
        final var ids = new TreeSet<String>();
        for (final var event : dispatched) {
            event.getEntries().stream().map(DbEntry::get_id).forEach(ids::add);
        }
        return ids;
    }

    @Test
    public void aMidBatchCheckpointCountsAsDurablePrefix() throws Exception {
        installAfterTrigger();
        TestUtils.setPrivateField(configuration, "walCheckpointBytes", CHECKPOINT_BYTES);
        refuseTheGroupForceAfterTheCheckpoint();

        final List<OperationResponse> responses = fixture.runQueued(
                List.of(() -> fixture.save("w1", "x".repeat(LARGE_VALUE_CHARS)), () -> fixture.save("w2", "small")));
        recorder.refusingWhen((_, _) -> false);

        assertTrue(recorder.forcedNames().contains(REWRITTEN_WAL), "the first write checkpointed the log");
        assertEquals(OperationStatus.OK, responses.getFirst().getStatus(),
                "the checkpoint made the first write durable, so it stands: " + responses.getFirst().getMessage());
        assertEquals(ErrorCode.ERROR_SAVING.getCode(), responses.get(1).getErrorCode());
        assertEquals("x".repeat(LARGE_VALUE_CHARS), fixture.valueOf("w1"));
        assertNull(fixture.valueOf("w2"));
        final var ids = dispatchedIds();
        assertTrue(ids.contains("w1"), "the durable write's after-trigger still runs: " + ids);
        assertFalse(ids.contains("w2"), "the taken-back write's trigger never runs: " + ids);
    }
}
