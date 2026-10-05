package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerRunRecoveryStagedTest {
    private static final Configuration configuration = Configuration.getInstance();
    private static final long ABSENT = AdminTriggerRunEntry.ABSENT_VERSION;

    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final CopyOnWriteArrayList<TriggerEvent> requeued = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(TriggerExecutor.class).stop();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        requeued.clear();
        triggerExecutor.stop();
        triggerExecutor.start(requeued::add);
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
    }

    private static JsonObject document(String id, String value) {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty("v", value);
        return object;
    }

    private void save(String id, String value) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id, value));
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void delete(String id) {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private long versionOf(String id) throws Exception {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
        return primaryKeyIndex.get(Collections.binarySearch(primaryKeyIndex, id)).getVersion();
    }

    private static DbEntry entry(String id) {
        return DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, document(id, "captured"));
    }

    private static void stage(EventType type, boolean batchMode, List<DbEntry> entries, Map<String, Long> versions) {
        TriggerRunLog.recordStaged(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit",
                "recalc", type, batchMode, "alice", 0, System.currentTimeMillis(), entries), versions);
    }

    private void recover() throws Exception {
        TriggerRunRecovery.recoverLocal(TriggerRunLog.pendingRunIds());
        Thread.sleep(100L);
    }

    private static List<String> idsOf(TriggerEvent event) {
        return event.getEntries().stream().map(DbEntry::get_id).toList();
    }

    @Test
    public void test_a_staged_save_whose_version_changed_is_confirmed_and_requeued() throws Exception {
        save("doc", "before");
        stage(EventType.UPDATED, false, List.of(entry("doc")), Map.of("doc", versionOf("doc")));
        save("doc", "after");

        recover();

        assertEquals(1, requeued.size());
        assertEquals(List.of("doc"), idsOf(requeued.getFirst()));
        final var confirmed = TriggerRunLog.pending().getFirst();
        assertEquals(TriggerRunStatus.PENDING, confirmed.getStatus());
        assertTrue(confirmed.getPriorVersions().isEmpty());
    }

    @Test
    public void test_a_staged_save_whose_version_is_unchanged_is_consumed() throws Exception {
        save("untouched", "same");
        stage(EventType.UPDATED, false, List.of(entry("untouched")), Map.of("untouched", versionOf("untouched")));

        recover();

        assertTrue(requeued.isEmpty(), "a write that never landed must not fire its trigger");
        assertTrue(TriggerRunLog.pending().isEmpty());
    }

    @Test
    public void test_a_staged_create_whose_id_is_absent_is_consumed() throws Exception {
        stage(EventType.CREATED, false, List.of(entry("ghost")), Map.of("ghost", ABSENT));

        recover();

        assertTrue(requeued.isEmpty());
        assertTrue(TriggerRunLog.pending().isEmpty());
    }

    @Test
    public void test_a_staged_delete_whose_id_is_gone_fires_deleted() throws Exception {
        save("doomed", "x");
        stage(EventType.DELETED, false, List.of(entry("doomed")), Map.of("doomed", versionOf("doomed")));
        delete("doomed");

        recover();

        assertEquals(1, requeued.size());
        assertEquals(EventType.DELETED, requeued.getFirst().getType());
        assertEquals(List.of("doomed"), idsOf(requeued.getFirst()));
    }

    @Test
    public void test_a_staged_delete_whose_id_was_recreated_still_fires_deleted() throws Exception {
        save("phoenix", "x");
        stage(EventType.DELETED, false, List.of(entry("phoenix")), Map.of("phoenix", versionOf("phoenix")));
        delete("phoenix");
        save("phoenix", "reborn");

        recover();

        assertEquals(1, requeued.size());
        assertEquals(EventType.DELETED, requeued.getFirst().getType());
    }

    @Test
    public void test_a_staged_batch_is_narrowed_to_its_landed_ids() throws Exception {
        stage(EventType.CREATED, true, List.of(entry("landed"), entry("lost")),
                Map.of("landed", ABSENT, "lost", ABSENT));
        save("landed", "x");

        recover();

        assertEquals(1, requeued.size());
        assertEquals(List.of("landed"), idsOf(requeued.getFirst()));
        assertEquals(List.of("landed"), TriggerRunLog.pending().getFirst().getIds());
    }
}
