package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.resp.DeleteManyResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class ConditionalDeleteTriggerTest {
    private static final String ACTOR = "alice";
    private static final String RUNS_WAL = Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME + Globals.WAL_EXTENSION;
    private static final long WAIT_MS = 10_000L;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final Configuration configuration = Configuration.getInstance();
    private final List<TriggerEvent> dispatched = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        triggerExecutor.stop();
        TriggerTestSupport.consumeAllPendingRuns();
        triggerExecutor.start(dispatched::add);
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var documents = new ArrayList<JsonObject>();
        for (var i = 0; i < 6; i++) {
            final var document = new JsonObject();
            document.add(Globals.PK_FIELD, new JsonString("t" + i));
            document.add("n", new JsonNumber(i));
            documents.add(document);
        }
        request.setObjects(documents);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        triggerExecutor.stop();
        TriggerTestSupport.consumeAllPendingRuns();
        TriggerTestSupport.clearTriggersAndProcedures();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void installDeletedTrigger(String mode) throws Exception {
        final var procedure = ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "audit", "return 1;"), ACTOR);
        assertEquals(OperationStatus.OK, procedure.getStatus(), procedure.getMessage());
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.DELETED)), "audit", mode,
                        TriggerDefinition.TIMING_AFTER, false, true, ACTOR, 1L, 1L, 1L, ACTOR)));
    }

    private OperationResponse deleteWhereNBelow(int bound) {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setCondition(new FieldOperator(FieldOperatorType.SMALLER_THAN, "n", new JsonNumber(bound)));
        return processor.processMessage(request);
    }

    private List<TriggerEvent> awaitDispatches() {
        assertTrue(triggerExecutor.drain(WAIT_MS), "the trigger queue never drained");
        triggerExecutor.start(dispatched::add);
        return dispatched;
    }

    private static Set<String> idsOf(List<TriggerEvent> events) {
        final var ids = new TreeSet<String>();
        for (final var event : events) {
            event.getEntries().stream().map(DbEntry::get_id).forEach(ids::add);
        }
        return ids;
    }

    @Test
    public void test_a_deleted_trigger_fires_once_per_matched_document() throws Exception {
        installDeletedTrigger(TriggerDefinition.MODE_DOCUMENT);

        assertEquals(3L, assertInstanceOf(DeleteManyResponse.class, deleteWhereNBelow(3)).getDeletedCount());

        final var events = awaitDispatches();
        assertEquals(3, events.size());
        assertEquals(Set.of("t0", "t1", "t2"), idsOf(events));
        assertTrue(events.stream().allMatch(event -> event.getType() == EventType.DELETED));
    }

    @Test
    public void test_a_batch_trigger_fires_once_for_the_whole_conditional_delete() throws Exception {
        installDeletedTrigger(TriggerDefinition.MODE_BATCH);

        assertEquals(4L, assertInstanceOf(DeleteManyResponse.class, deleteWhereNBelow(4)).getDeletedCount());

        final var events = awaitDispatches();
        assertEquals(1, events.size());
        assertEquals(Set.of("t0", "t1", "t2", "t3"), idsOf(events));
    }

    @Test
    public void test_the_staged_runs_are_one_forced_record() throws Exception {
        installDeletedTrigger(TriggerDefinition.MODE_DOCUMENT);
        final long runForces;
        try (var recorder = RecordingForcer.install(true)) {
            assertEquals(OperationStatus.OK, deleteWhereNBelow(5).getStatus());
            runForces = recorder.forcedNames().stream().filter(RUNS_WAL::equals).count();
        }

        assertEquals(1L, runForces, "five document-mode runs staged in one forced trigger_runs record");
        assertEquals(5, awaitDispatches().size());
    }

    @Test
    public void test_a_conditional_delete_matching_nothing_fires_nothing() throws Exception {
        installDeletedTrigger(TriggerDefinition.MODE_DOCUMENT);

        assertEquals(0L, assertInstanceOf(DeleteManyResponse.class, deleteWhereNBelow(0)).getDeletedCount());

        assertEquals(List.of(), awaitDispatches());
        assertEquals(List.of(), TriggerRunLog.pending());
    }
}
