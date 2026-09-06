package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.data.ProcedureDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminAntiEntropyProcedureTest {
    private final AdminAntiEntropyService service = IocContainer.get(AdminAntiEntropyService.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clear() {
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
    }

    private void writeProcedure(String name, long version, String source) throws Exception {
        final var definition = new ProcedureDefinition(name, source, version, null, true, 1L, 1L, "alice");
        fs.writeProcedure(TestGlobals.DB, name, eJson.toJson(definition.toJsonObject()));
        cache.putProcedure(TestGlobals.DB, definition);
    }

    private void writeTrigger() throws Exception {
        final var definition = new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "p",
                TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner");
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL,
                eJson.toJson(TriggerDefinition.toFileJson(List.of(definition))));
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of(definition));
    }

    private static void conform(AdminAntiEntropyService target, AdminSnapshotPayload snapshot) throws Exception {
        final var method = AdminAntiEntropyService.class.getDeclaredMethod("conform", AdminSnapshotPayload.class);
        method.setAccessible(true);
        method.invoke(target, snapshot);
    }

    private AdminSnapshotPayload snapshotWithout() {
        final var snapshot = service.buildSnapshot();
        final var keep = Set.of(new String[]{"keep"});
        final var procedures = new JsonObject();
        for (final var entry : snapshot.getProcedures().entrySet()) {
            final var name = entry.getKey().substring(entry.getKey().indexOf('|') + 1);
            if (keep.contains(name)) {
                procedures.add(entry.getKey(), entry.getValue());
            }
        }
        snapshot.setProcedures(procedures);
        return snapshot;
    }

    @Test
    public void test_snapshot_carries_procedures() throws Exception {
        writeProcedure("recalc", 3L, "return 1;");
        final var snapshot = service.buildSnapshot();
        final var key = TestGlobals.DB + "|recalc";
        assertTrue(snapshot.getProcedures().has(key));
        assertEquals(3d, snapshot.getProcedures().get(key).asJsonObject().get("version").asJsonNumber().getValue()
                .doubleValue());
    }

    @Test
    public void test_snapshot_carries_triggers() throws Exception {
        writeTrigger();
        final var snapshot = service.buildSnapshot();
        final var key = TestGlobals.DB + "|" + TestGlobals.COLL;
        assertTrue(snapshot.getTriggers().has(key));
        assertEquals(1, snapshot.getTriggers().get(key).asJsonArray().asList().size());
    }

    @Test
    public void test_snapshot_omits_absent_procedures_and_triggers() {
        final var snapshot = service.buildSnapshot();
        assertFalse(snapshot.getProcedures().has(TestGlobals.DB + "|recalc"));
        assertFalse(snapshot.getTriggers().has(TestGlobals.DB + "|" + TestGlobals.COLL));
    }

    // A peer on an older version omits the field entirely; it must read as empty rather than null
    @Test
    public void test_snapshot_from_older_peer_without_the_fields_deserializes() {
        final var legacy = new AdminSnapshotPayload();
        assertNotNull(legacy.getProcedures());
        assertNotNull(legacy.getTriggers());
        legacy.setProcedures(null);
        legacy.setTriggers(null);
        assertNotNull(legacy.getProcedures());
        assertNotNull(legacy.getTriggers());
        final var fiveArg = new AdminSnapshotPayload(1L, List.of(), List.of(), List.of(), new JsonObject());
        assertNotNull(fiveArg.getProcedures());
        assertNotNull(fiveArg.getTriggers());
    }

    @Test
    public void test_conform_writes_a_missing_procedure() throws Exception {
        writeProcedure("fromPeer", 5L, "return 5;");
        final var snapshot = service.buildSnapshot();
        // Wipe locally, then conform: the snapshot must put it back.
        fs.deleteProcedure(TestGlobals.DB, "fromPeer");
        cache.removeProceduresForDatabase(TestGlobals.DB);
        assertNull(cache.getProcedure(TestGlobals.DB, "fromPeer"));

        conform(service, snapshot);
        final var restored = cache.getProcedure(TestGlobals.DB, "fromPeer");
        assertNotNull(restored);
        assertEquals(5L, restored.getVersion());
        assertEquals("return 5;", restored.getSource());
    }

    @Test
    public void test_conform_deletes_a_procedure_absent_from_the_snapshot() throws Exception {
        writeProcedure("keep", 1L, "return 1;");
        writeProcedure("stale", 1L, "return 2;");
        conform(service, snapshotWithout());
        assertNotNull(cache.getProcedure(TestGlobals.DB, "keep"));
        assertNull(cache.getProcedure(TestGlobals.DB, "stale"));
        assertFalse(fs.listProcedureNames(TestGlobals.DB).contains("stale"));
    }

    @Test
    public void test_conform_updates_a_diverged_procedure() throws Exception {
        writeProcedure("recalc", 9L, "return 9;");
        final var snapshot = service.buildSnapshot();
        writeProcedure("recalc", 1L, "return 1;");
        conform(service, snapshot);
        assertEquals(9L, cache.getProcedure(TestGlobals.DB, "recalc").getVersion());
    }

    @Test
    public void test_conform_writes_and_removes_triggers() throws Exception {
        writeTrigger();
        final var withTrigger = service.buildSnapshot();
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());

        conform(service, withTrigger);
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());

        final var withoutTrigger = service.buildSnapshot();
        withoutTrigger.setTriggers(new JsonObject());
        conform(service, withoutTrigger);
        assertTrue(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    // The periodic sweep runs the same reconciliation, so conforming an already-matching state is a no-op
    @Test
    public void test_conform_is_idempotent() throws Exception {
        writeProcedure("recalc", 1L, "return 1;");
        writeTrigger();
        final var snapshot = service.buildSnapshot();
        conform(service, snapshot);
        conform(service, snapshot);
        assertEquals(1L, cache.getProcedure(TestGlobals.DB, "recalc").getVersion());
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());
    }
    // The regression this guards: buildSnapshot used to pull every procedure and trigger through the cache,
    // so a clustered node ended up holding all of them regardless of what it actually used.
    @Test
    public void test_snapshot_does_not_populate_the_metadata_caches() throws Exception {
        writeProcedure("snapshotted", 1L, "return 1;");
        cache.removeProceduresForDatabase(TestGlobals.DB);
        cache.removeTriggersMatching(_ -> true);
        final var before = cache.metadataCacheStats();

        service.buildSnapshot();

        final var after = cache.metadataCacheStats();
        assertEquals(before.procedureEntries(), after.procedureEntries(),
                "building a snapshot must not warm the procedure cache");
        assertEquals(before.triggerEntries(), after.triggerEntries(),
                "building a snapshot must not warm the trigger cache");
    }
}
