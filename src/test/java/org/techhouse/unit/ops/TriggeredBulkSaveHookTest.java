package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TriggerTestSupport;

public class TriggeredBulkSaveHookTest {
    private static final String RUNS_LOG = Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME + Globals.WAL_EXTENSION;
    private static final String REFUSING_HOOK = "export default function (doc) {"
            + " if (doc._id === 'hook-refused') { throw new Error('refused by the hook'); } return doc; };";
    private final Cache cache = IocContainer.get(Cache.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUp() throws Exception {
        BeforeHookTestSupport.setUpAll();
    }

    @AfterAll
    static void tearDown() throws Exception {
        BeforeHookTestSupport.tearDownAll();
    }

    @BeforeEach
    void reset() throws Exception {
        BeforeHookTestSupport.reset();
        IocContainer.get(TriggerExecutor.class).stop();
        TriggerTestSupport.consumeAllPendingRuns();
        BeforeHookTestSupport.installHook("gate", "gate", REFUSING_HOOK, EventType.CREATED, EventType.UPDATED);
        final var triggers = new ArrayList<>(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL));
        triggers.add(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "recalc",
                TriggerDefinition.MODE_DOCUMENT, false, true, BeforeHookTestSupport.ACTOR, 1L, 1L, 1L,
                BeforeHookTestSupport.ACTOR));
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, triggers);
    }

    private static BulkSaveRequest bulkOf(String... ids) {
        final var objects = new ArrayList<JsonObject>();
        for (final var id : ids) {
            objects.add(BeforeHookTestSupport.document(id));
        }
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(objects);
        return request;
    }

    private boolean stored(String id) throws Exception {
        return !cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of(id)).isEmpty();
    }

    @Test
    public void test_a_hook_refusing_one_document_stages_no_trigger_run_for_any() throws Exception {
        final var bulk = bulkOf("hook-fine-a", "hook-refused", "hook-fine-b");

        try (var recorder = RecordingForcer.install(true)) {
            final var response = processor.processMessage(bulk);

            assertEquals(OperationStatus.ERROR, response.getStatus());
            assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), response.getErrorCode());
            assertTrue(recorder.forcedNames().stream().noneMatch(RUNS_LOG::equals),
                    "a refused bulk must not stage anything");
        }
        assertTrue(TriggerRunLog.pending().isEmpty());
        assertFalse(stored("hook-fine-a") || stored("hook-fine-b"), "the refusal covers the whole bulk");
    }

    @Test
    public void test_a_hook_accepting_every_document_stages_one_run_each_in_one_record() throws Exception {
        final var bulk = bulkOf("hook-ok-a", "hook-ok-b", "hook-ok-c");

        try (var recorder = RecordingForcer.install(true)) {
            assertEquals(OperationStatus.OK, processor.processMessage(bulk).getStatus());

            assertEquals(1L, recorder.forcedNames().stream().filter(RUNS_LOG::equals).count());
        }
        final var records = TriggerRunLog.pending();
        assertEquals(List.of("hook-ok-a", "hook-ok-b", "hook-ok-c"),
                records.stream().flatMap(record -> record.getIds().stream()).sorted().toList());
        assertTrue(records.stream().map(AdminTriggerRunEntry::getStatus).allMatch(TriggerRunStatus.STAGED::equals));
    }
}
