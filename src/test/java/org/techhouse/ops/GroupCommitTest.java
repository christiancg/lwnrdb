package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
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
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class GroupCommitTest {
    private static final long WAIT_MS = 10_000L;
    private static final long QUEUE_POLL_NANOS = 1_000_000L;
    private static final String WAL = TestGlobals.COLL + ".wal";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        recorder = RecordingForcer.install(true);
        assertEquals(OperationStatus.OK, save("seed", "v0").getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        recorder.close();
        TestUtils.setPrivateField(Configuration.getInstance(), "triggersEnabled", false);
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static SaveRequest saveRequest(String id, String value) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty("v", value);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        return request;
    }

    private OperationResponse save(String id, String value) {
        return processor.processMessage(saveRequest(id, value));
    }

    private OperationResponse delete(String id) {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request);
    }

    private String valueOf(String id) {
        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id(id);
        final var response = processor.processMessage(find);
        if (!(response instanceof FindByIdResponse found)) {
            return null;
        }
        return found.getObject().get("v").asJsonString().getValue();
    }

    private long walForces() {
        return recorder.forcedNames().stream().filter(WAL::equals).count();
    }

    private static void awaitQueued(int expected) {
        final var deadline = System.currentTimeMillis() + WAIT_MS;
        while (GroupCommit.queuedFor(TestGlobals.DB, TestGlobals.COLL) < expected) {
            assertTrue(System.currentTimeMillis() < deadline, "the writers never queued");
            LockSupport.parkNanos(QUEUE_POLL_NANOS);
        }
    }

    private List<OperationResponse> runQueued(List<Supplier<OperationResponse>> writes) throws Exception {
        final var responses = new ConcurrentHashMap<Integer, OperationResponse>();
        final var threads = new ArrayList<Thread>();
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            for (var index = 0; index < writes.size(); index++) {
                final var slot = index;
                final var write = writes.get(index);
                threads.add(Thread.ofVirtual().start(() -> responses.put(slot, write.get())));
                awaitQueued(index + 1);
            }
            recorder.clear();
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
        for (final var thread : threads) {
            thread.join(WAIT_MS);
        }
        final var ordered = new ArrayList<OperationResponse>();
        for (var index = 0; index < writes.size(); index++) {
            ordered.add(responses.get(index));
        }
        return ordered;
    }

    private static List<Supplier<OperationResponse>> times(int count, IntFunction<OperationResponse> write) {
        final var writes = new ArrayList<Supplier<OperationResponse>>();
        for (var index = 0; index < count; index++) {
            final var number = index;
            writes.add(() -> write.apply(number));
        }
        return writes;
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
        recorder.failingFor(WAL);

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
    public void test_a_write_that_fires_a_trigger_or_runs_deeper_is_not_grouped() throws Exception {
        assertTrue(GroupCommit.forSave(saveRequest("x", "v")).getAsBoolean());
        final var nested = saveRequest("x", "v");
        nested.setTriggerDepth(1);
        assertFalse(GroupCommit.forSave(nested).getAsBoolean());
        final var followOn = saveRequest("x", "v");
        followOn.setBackgroundFollowOn(true);
        assertFalse(GroupCommit.forSave(followOn).getAsBoolean());

        TestUtils.setPrivateField(Configuration.getInstance(), "triggersEnabled", true);
        final var procedure = ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "recalc", "return 1;"), "alice");
        final var trigger = TriggerOperationHelper.executeSave(
                new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t", List.of("CREATED", "DELETED"), "recalc"),
                "alice");
        assertEquals(OperationStatus.OK, procedure.getStatus(), procedure.getMessage());
        assertEquals(OperationStatus.OK, trigger.getStatus(), trigger.getMessage());

        assertFalse(GroupCommit.forSave(saveRequest("x", "v")).getAsBoolean());
        final var deleteRequest = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        deleteRequest.set_id("seed");
        assertFalse(GroupCommit.forDelete(deleteRequest).getAsBoolean());
    }

    @Test
    public void test_without_synced_writes_writes_take_the_plain_path() throws Exception {
        recorder.close();
        recorder = RecordingForcer.install(false);
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
    }
}
