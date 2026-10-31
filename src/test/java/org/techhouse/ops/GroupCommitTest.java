package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class GroupCommitTest {
    private static final long WAIT_MS = GroupCommitFixture.WAIT_MS;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private RecordingForcer recorder;
    private GroupCommitFixture fixture;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        recorder = RecordingForcer.install(true);
        fixture = new GroupCommitFixture(recorder);
        assertEquals(OperationStatus.OK, save("seed", "v0").getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        recorder.close();
        TestUtils.setPrivateField(Configuration.getInstance(), "triggersEnabled", false);
        fs.metadataFiles().deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static SaveRequest saveRequest(String id, String value) {
        return GroupCommitFixture.saveRequest(id, value);
    }

    private OperationResponse save(String id, String value) {
        return fixture.save(id, value);
    }

    private OperationResponse delete(String id) {
        return fixture.delete(id);
    }

    private String valueOf(String id) {
        return fixture.valueOf(id);
    }

    private long walForces() {
        return fixture.walForces();
    }

    private List<OperationResponse> runQueued(List<Supplier<OperationResponse>> writes) throws Exception {
        return fixture.runQueued(writes);
    }

    private static List<Supplier<OperationResponse>> times(int count, IntFunction<OperationResponse> write) {
        return GroupCommitFixture.times(count, write);
    }

    @Test
    public void test_writers_queued_behind_one_hold_share_one_force() throws Exception {
        final var responses = runQueued(times(8, number -> save("n" + number, "v" + number)));

        assertTrue(responses.stream().allMatch(response -> response.getStatus() == OperationStatus.OK));
        assertEquals(1L, walForces());
        for (var number = 0; number < 8; number++) {
            assertEquals("v" + number, valueOf("n" + number));
        }
        assertEquals(0, GroupCommit.queuedFor(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_each_writer_gets_its_own_response() throws Exception {
        assertEquals(OperationStatus.OK, save("kept", "v0").getStatus());
        final var mismatched = saveRequest("other", "x");
        mismatched.set_id("not-other");

        final var responses = runQueued(List.of(() -> save("fresh", "v1"), () -> save("seed", "v2"),
                () -> processor.processMessage(mismatched), () -> delete("kept")));

        assertEquals(OperationStatus.OK, responses.get(0).getStatus());
        assertEquals(OperationStatus.OK, responses.get(1).getStatus());
        assertEquals(ErrorCode.VALIDATION_ERROR.getCode(), responses.get(2).getErrorCode());
        assertEquals(OperationStatus.OK, responses.get(3).getStatus());
        assertEquals("v1", valueOf("fresh"));
        assertEquals("v2", valueOf("seed"));
        assertNull(valueOf("kept"));
        assertEquals(1L, walForces());
    }

    @Test
    public void test_a_failed_force_answers_every_writer_with_its_error_and_takes_the_writes_back() throws Exception {
        recorder.failingFor(GroupCommitFixture.WAL);

        final var responses = runQueued(
                List.of(() -> save("lost-1", "v"), () -> save("seed", "changed"), () -> delete("seed")));
        recorder.failingFor(null);

        assertEquals(ErrorCode.ERROR_SAVING.getCode(), responses.get(0).getErrorCode());
        assertEquals(ErrorCode.ERROR_SAVING.getCode(), responses.get(1).getErrorCode());
        assertEquals(ErrorCode.ERROR_DELETING.getCode(), responses.get(2).getErrorCode());
        assertNull(valueOf("lost-1"));
        assertEquals("v0", valueOf("seed"));
        assertEquals(OperationStatus.OK, save("after", "v").getStatus(), "the collection keeps working");
        assertEquals("v", valueOf("after"));
    }

    @Test
    public void test_the_same_id_written_twice_in_one_group_ends_with_the_later_write() throws Exception {
        final var responses = runQueued(List.of(() -> save("seed", "first"), () -> save("seed", "second")));

        assertTrue(responses.stream().allMatch(response -> response.getStatus() == OperationStatus.OK));
        assertEquals("second", valueOf("seed"));
        assertEquals(1L, walForces());
    }

    @Test
    public void test_a_write_that_runs_deeper_or_follows_on_is_not_grouped() throws Exception {
        assertTrue(GroupCommit.forSave(saveRequest("x", "v")).groupable().getAsBoolean());
        final var nested = saveRequest("x", "v");
        nested.setTriggerDepth(1);
        assertFalse(GroupCommit.forSave(nested).groupable().getAsBoolean());
        final var followOn = saveRequest("x", "v");
        followOn.setBackgroundFollowOn(true);
        assertFalse(GroupCommit.forSave(followOn).groupable().getAsBoolean());
        final var nestedDelete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        nestedDelete.set_id("seed");
        nestedDelete.setTriggerDepth(1);
        assertFalse(GroupCommit.forDelete(nestedDelete).groupable().getAsBoolean());
        assertFalse(GroupCommit.forSave(saveRequest("x", "v")).sideEffects().getAsBoolean());

        TestUtils.setPrivateField(Configuration.getInstance(), "triggersEnabled", true);
        final var procedure = ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "recalc", "return 1;"), "alice");
        final var trigger = TriggerOperationHelper.executeSave(
                new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t", List.of("CREATED", "DELETED"), "recalc"),
                "alice");
        assertEquals(OperationStatus.OK, procedure.getStatus(), procedure.getMessage());
        assertEquals(OperationStatus.OK, trigger.getStatus(), trigger.getMessage());

        final var triggered = GroupCommit.forSave(saveRequest("x", "v"));
        assertTrue(triggered.groupable().getAsBoolean());
        assertTrue(triggered.sideEffects().getAsBoolean());
        assertEquals("x", triggered.id());
        final var deleteRequest = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        deleteRequest.set_id("seed");
        final var triggeredDelete = GroupCommit.forDelete(deleteRequest);
        assertTrue(triggeredDelete.groupable().getAsBoolean());
        assertTrue(triggeredDelete.sideEffects().getAsBoolean());
        assertEquals("seed", triggeredDelete.id());
    }

    @Test
    public void test_without_synced_writes_writes_take_the_plain_path() throws Exception {
        recorder.close();
        recorder = RecordingForcer.install(false);
        fixture.useRecorder(recorder);
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        final Thread writer;
        try {
            writer = Thread.ofVirtual().start(() -> save("plain", "v"));
            Thread.sleep(50);
            assertEquals(0, GroupCommit.queuedFor(TestGlobals.DB, TestGlobals.COLL));
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
        writer.join(WAIT_MS);

        assertEquals("v", valueOf("plain"));
    }

    @Test
    public void test_more_writers_than_the_cap_are_served_by_successive_leaders() throws Exception {
        final var count = GroupCommit.MAX_GROUP_OPS + 6;

        final var responses = runQueued(times(count, number -> save("m" + number, "v")));

        assertTrue(responses.stream().allMatch(response -> response.getStatus() == OperationStatus.OK));
        assertEquals(2L, walForces());
    }

    @Test
    public void test_a_single_queued_write_forces_once() throws Exception {
        final var responses = runQueued(List.of(() -> save("alone", "v")));

        assertEquals(OperationStatus.OK, responses.getFirst().getStatus());
        assertEquals(1L, walForces());
        assertEquals("v", valueOf("alone"));
    }

    @Test
    public void test_the_lock_is_released_by_the_thread_that_took_it() throws Exception {
        runQueued(times(4, number -> save("r" + number, "v")));

        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL), "no writer stranded the collection lock");
        locks.release(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_a_check_that_cannot_read_its_metadata_runs_the_write_alone() {
        assertFalse(GroupCommit.groupable(() -> {
            throw new IllegalStateException("triggers file unreadable");
        }));
        assertTrue(GroupCommit.groupable(() -> true));
        assertTrue(GroupCommit.sideEffecting(() -> {
            throw new IllegalStateException("triggers file unreadable");
        }));
        assertFalse(GroupCommit.sideEffecting(() -> false));
    }

    @Test
    public void test_a_save_names_its_object_id_when_the_request_has_none() {
        final var request = saveRequest("from-object", "v");
        assertEquals("from-object", GroupCommit.forSave(request).id());
        final var generated = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        generated.setObject(new JsonObject());
        assertNull(GroupCommit.forSave(generated).id());
        final var numeric = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.addProperty("_id", 7);
        numeric.setObject(object);
        assertNull(GroupCommit.forSave(numeric).id());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> queues() throws Exception {
        final var field = GroupCommit.class.getDeclaredField("queues");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(null);
    }

    @Test
    public void test_a_dropped_collection_leaves_no_queue() throws Exception {
        final var collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(queues().containsKey(collId), "the seed save queued itself");

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());
        assertFalse(queues().containsKey(collId));

        GroupCommit.queuedFor(TestGlobals.DB, "other_coll");
        GroupCommit.queuedFor("other_db", TestGlobals.COLL);
        DroppedCollections.forgetDatabase(TestGlobals.DB);
        assertFalse(queues().containsKey(Cache.getCollectionIdentifier(TestGlobals.DB, "other_coll")));
        assertTrue(queues().containsKey(Cache.getCollectionIdentifier("other_db", TestGlobals.COLL)));
        GroupCommit.forgetDatabase("other_db");
        assertFalse(queues().containsKey(Cache.getCollectionIdentifier("other_db", TestGlobals.COLL)));
    }

    @Test
    public void test_a_writer_queued_when_its_queue_is_forgotten_is_served() throws Exception {
        final OperationResponse[] response = new OperationResponse[1];
        final Thread writer;
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            writer = Thread.ofVirtual().start(() -> response[0] = save("late", "v"));
            GroupCommitFixture.awaitQueued(1);
            GroupCommit.forget(TestGlobals.DB, TestGlobals.COLL);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
        writer.join(WAIT_MS);

        assertFalse(writer.isAlive(), "the writer served itself from the queue it held");
        assertEquals(OperationStatus.OK, response[0].getStatus());
        assertEquals("v", valueOf("late"));
        assertEquals(0, GroupCommit.queuedFor(TestGlobals.DB, TestGlobals.COLL));
    }
}
