package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;

final class GroupCommitFixture {
    static final long WAIT_MS = 10_000L;
    static final String WAL = TestGlobals.COLL + ".wal";
    private static final long QUEUE_POLL_NANOS = 1_000_000L;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private RecordingForcer recorder;

    GroupCommitFixture(RecordingForcer recorder) {
        this.recorder = recorder;
    }

    void useRecorder(RecordingForcer replacement) {
        recorder = replacement;
    }

    static SaveRequest saveRequest(String id, String value) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty("v", value);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        return request;
    }

    OperationResponse save(String id, String value) {
        return processor.processMessage(saveRequest(id, value));
    }

    OperationResponse delete(String id) {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request);
    }

    OperationResponse process(OperationRequest request) {
        return processor.processMessage(request);
    }

    String valueOf(String id) {
        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id(id);
        final var response = processor.processMessage(find);
        if (!(response instanceof FindByIdResponse found)) {
            return null;
        }
        return found.getObject().get("v").asJsonString().getValue();
    }

    long walForces() {
        return forcesOf(WAL);
    }

    long forcesOf(String name) {
        return recorder.forcedNames().stream().filter(name::equals).count();
    }

    static void awaitQueued(int expected) {
        final var deadline = System.currentTimeMillis() + WAIT_MS;
        while (GroupCommit.queuedFor(TestGlobals.DB, TestGlobals.COLL) < expected) {
            assertTrue(System.currentTimeMillis() < deadline, "the writers never queued");
            LockSupport.parkNanos(QUEUE_POLL_NANOS);
        }
    }

    List<OperationResponse> runQueued(List<Supplier<OperationResponse>> writes) throws Exception {
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

    CompletableFuture<List<OperationResponse>> runQueuedInBackground(List<Supplier<OperationResponse>> writes) {
        final var result = new CompletableFuture<List<OperationResponse>>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(runQueued(writes));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    static List<Supplier<OperationResponse>> times(int count, IntFunction<OperationResponse> write) {
        final var writes = new ArrayList<Supplier<OperationResponse>>();
        for (var index = 0; index < count; index++) {
            final var number = index;
            writes.add(() -> write.apply(number));
        }
        return writes;
    }
}
