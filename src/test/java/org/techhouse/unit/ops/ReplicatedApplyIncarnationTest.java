package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.admin.CollectionIncarnation;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReplicatedApplyIncarnationTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

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

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    private void create(String collName) {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, collName)).getStatus());
    }

    private OperationStatus findStatus(String collName, String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, collName);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    private static ReplicationPayload upsert(String collName, long incarnation, String id) {
        final var payload = new ReplicationPayload(TestGlobals.DB, collName, ReplicationOp.UPSERT, List.of(doc(id)),
                null, List.of("100"));
        payload.setIncarnationValue(incarnation);
        return payload;
    }

    @Test
    public void test_an_upsert_of_the_current_incarnation_applies() {
        create("incCurrent");
        final var incarnation = CollectionIncarnation.current(TestGlobals.DB, "incCurrent");

        assertTrue(ReplicatedApplyHelper.apply(upsert("incCurrent", incarnation, "a")));
        assertEquals(OperationStatus.OK, findStatus("incCurrent", "a"));
    }

    @Test
    public void test_an_upsert_of_another_incarnation_is_refused() {
        create("incOther");
        final var incarnation = CollectionIncarnation.current(TestGlobals.DB, "incOther");

        assertFalse(ReplicatedApplyHelper.apply(upsert("incOther", incarnation + 1, "a")));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("incOther", "a"));
    }

    @Test
    public void test_a_stamped_upsert_to_an_unregistered_collection_writes_nothing() {
        assertFalse(ReplicatedApplyHelper.apply(upsert("incNeverCreated", 42L, "a")));
        assertFalse(new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + "incNeverCreated")
                .exists());
    }

    @Test
    public void test_an_unstamped_upsert_applies_as_before() {
        create("incLegacy");

        assertTrue(ReplicatedApplyHelper.apply(upsert("incLegacy", 0L, "a")));
        assertEquals(OperationStatus.OK, findStatus("incLegacy", "a"));
    }

    @Test
    public void test_a_delete_of_another_incarnation_is_refused() throws Exception {
        create("incDelete");
        final var incarnation = CollectionIncarnation.current(TestGlobals.DB, "incDelete");
        assertTrue(ReplicatedApplyHelper.apply(upsert("incDelete", incarnation, "kept")));

        final var delete = new ReplicationPayload(TestGlobals.DB, "incDelete", ReplicationOp.DELETE, null,
                List.of("kept"), List.of("200"));
        delete.setIncarnationValue(incarnation + 1);

        assertFalse(ReplicatedApplyHelper.apply(delete));
        assertEquals(OperationStatus.OK, findStatus("incDelete", "kept"));
        assertFalse(fs.tombstones().read(TestGlobals.DB, "incDelete").containsKey("kept"));
    }

    @Test
    public void test_a_payload_built_before_a_drop_and_recreate_does_not_seed_the_new_collection() {
        create("incRecreated");
        final var before = CollectionIncarnation.current(TestGlobals.DB, "incRecreated");
        final var stale = upsert("incRecreated", before, "dead");

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.DB, "incRecreated")).getStatus());
        create("incRecreated");

        assertNotEquals(before, CollectionIncarnation.current(TestGlobals.DB, "incRecreated"));
        assertFalse(ReplicatedApplyHelper.apply(stale));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("incRecreated", "dead"),
                "a pre-drop document applied into the new incarnation is replicated cluster-wide from there");
    }
}
