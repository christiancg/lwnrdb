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
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminAntiEntropyUnreadableTest {
    private static final String SCHEMA = "{\"type\":\"object\"}";
    private static final String TORN = "{\"name\": \"tor";
    private static final String PROCEDURE = "proc";
    private static final String SCHEDULE = "nightly";

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
    void writeEveryDefinition() throws Exception {
        writeProcedure();
        writeSchedule();
        writeSchema();
        writeTriggers();
    }

    @Test
    public void test_build_snapshot_lists_every_unreadable_kind() throws Exception {
        tearEveryDefinition();

        final var snapshot = service.buildSnapshot();

        assertEquals(Set.of(key("procedure", PROCEDURE), key("schedule", SCHEDULE), key("schema", TestGlobals.COLL),
                key("triggers", TestGlobals.COLL)), Set.copyOf(snapshot.getUnreadable()));
    }

    @Test
    public void test_a_readable_snapshot_lists_nothing_unreadable() {
        assertTrue(service.buildSnapshot().getUnreadable().isEmpty());
    }

    @Test
    public void test_an_unreadable_procedure_in_the_snapshot_is_not_deleted_locally() throws Exception {
        final var snapshot = snapshotTornThenRepaired();

        assertFalse(conform(snapshot));

        assertTrue(fs.listProcedureNames(TestGlobals.DB).contains(PROCEDURE));
        assertNotNull(cache.loadProcedureUncached(TestGlobals.DB, PROCEDURE));
    }

    @Test
    public void test_an_unreadable_schedule_in_the_snapshot_is_not_deleted_locally() throws Exception {
        final var snapshot = snapshotTornThenRepaired();

        conform(snapshot);

        assertTrue(fs.listScheduleNames(TestGlobals.DB).contains(SCHEDULE));
    }

    @Test
    public void test_an_unreadable_schema_in_the_snapshot_is_not_deleted_locally() throws Exception {
        final var snapshot = snapshotTornThenRepaired();

        conform(snapshot);

        assertNotNull(cache.loadSchemaUncached(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_unreadable_triggers_in_the_snapshot_are_not_deleted_locally() throws Exception {
        final var snapshot = snapshotTornThenRepaired();

        conform(snapshot);

        assertEquals(1, cache.loadTriggersUncached(TestGlobals.DB, TestGlobals.COLL).size());
    }

    @Test
    public void test_a_snapshot_without_the_field_still_deletes_what_it_omits() throws Exception {
        final var snapshot = snapshotTornThenRepaired();
        snapshot.setUnreadable(null);

        conform(snapshot);

        assertFalse(fs.listProcedureNames(TestGlobals.DB).contains(PROCEDURE));
        assertNull(cache.loadSchemaUncached(TestGlobals.DB, TestGlobals.COLL));
    }

    private AdminSnapshotPayload snapshotTornThenRepaired() throws Exception {
        tearEveryDefinition();
        final var snapshot = service.buildSnapshot();
        writeEveryDefinition();
        return snapshot;
    }

    private boolean conform(AdminSnapshotPayload snapshot) throws Exception {
        final var conformerField = AdminAntiEntropyService.class.getDeclaredField("conformer");
        conformerField.setAccessible(true);
        final var conformer = conformerField.get(service);
        final var method = conformer.getClass().getDeclaredMethod("conform", AdminSnapshotPayload.class);
        method.setAccessible(true);
        return (boolean) method.invoke(conformer, snapshot);
    }

    private static String key(String kind, String name) {
        return kind + "|" + Cache.getCollectionIdentifier(TestGlobals.DB, name);
    }

    private void tearEveryDefinition() throws Exception {
        fs.writeProcedure(TestGlobals.DB, PROCEDURE, TORN);
        fs.writeSchedule(TestGlobals.DB, SCHEDULE, TORN);
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, TORN);
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL, TORN);
        cache.removeProcedure(TestGlobals.DB, PROCEDURE);
        cache.removeSchedule(TestGlobals.DB, SCHEDULE);
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
    }

    private void writeProcedure() throws Exception {
        final var definition = new ProcedureDefinition(PROCEDURE, "return 1;", 1L, null, true, 1L, 1L, "alice");
        fs.writeProcedure(TestGlobals.DB, PROCEDURE, eJson.toJson(definition.toJsonObject()));
        cache.removeProcedure(TestGlobals.DB, PROCEDURE);
    }

    private void writeSchedule() throws Exception {
        final var definition = new ScheduleDefinition(SCHEDULE, PROCEDURE, null, 60_000L, null, 0L, true, "alice", null,
                1L, 1L, 1L, "alice");
        fs.writeSchedule(TestGlobals.DB, SCHEDULE, eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(TestGlobals.DB, SCHEDULE);
    }

    private void writeSchema() throws Exception {
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, SCHEMA);
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
    }

    private void writeTriggers() throws Exception {
        final var definition = new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), PROCEDURE,
                TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner");
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL,
                eJson.toJson(TriggerDefinition.toFileJson(List.of(definition))));
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
    }
}
