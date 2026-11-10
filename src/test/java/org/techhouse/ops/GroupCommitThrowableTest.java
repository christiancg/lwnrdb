package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class GroupCommitThrowableTest {
    private static final int CONCURRENT_SAVES = 48;
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final String SUBJECT = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private IntConsumer originalHalt;
    private RecordingForcer recorder;
    private GroupCommitFixture fixture;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalHalt = TestUtils.getPrivateField(faultInjector, "halt", IntConsumer.class);
        recorder = RecordingForcer.install(true);
        fixture = new GroupCommitFixture(recorder);
    }

    @AfterEach
    public void tearDown() throws Exception {
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
        TestUtils.setPrivateField(faultInjector, "halt", originalHalt);
        recorder.close();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static GroupCommit.GroupedWrite write(String id, GroupCommit.GroupedAttempt attempt) {
        return new GroupCommit.GroupedWrite(OperationType.SAVE, ErrorCode.ERROR_SAVING,
                new GroupCommit.Eligibility(() -> true, id, () -> false), attempt);
    }

    private static CompletableFuture<OperationResponse> queued(GroupCommit.GroupedWrite write) {
        return CompletableFuture.supplyAsync(() -> GroupCommit.run(TestGlobals.DB, TestGlobals.COLL, write),
                command -> Thread.ofVirtual().start(command));
    }

    @Test
    public void aThrowingBatchFailsItsWritesAndElectsNoLooper() throws Exception {
        final CompletableFuture<OperationResponse> throwing;
        final CompletableFuture<OperationResponse> other;
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            throwing = queued(write("bad", _ -> {
                throw new AssertionError("a batch member that throws an Error");
            }));
            GroupCommitFixture.awaitQueued(1);
            other = queued(write("ok",
                    _ -> GroupCommit.answered(new OperationResponse(OperationType.SAVE, OperationStatus.OK, "saved"))));
            GroupCommitFixture.awaitQueued(2);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        for (final var response : List.of(throwing, other)) {
            assertEquals(ErrorCode.ERROR_SAVING.getCode(),
                    response.get(GroupCommitFixture.WAIT_MS, TimeUnit.MILLISECONDS).getErrorCode());
        }
        assertEquals(OperationStatus.OK, fixture.save("after", "v").getStatus(), "the collection keeps working");
    }

    @Test
    public void aBatchThrowingAfterACommitAnswersTheSettledWriteAsDurable() throws Exception {
        final var responses = fixture.runQueued(List.of(() -> fixture.save("kept", "v"),
                () -> GroupCommit.run(TestGlobals.DB, TestGlobals.COLL, write("bad", _ -> () -> {
                    throw new AssertionError("a commit that throws an Error");
                }))));

        assertEquals(OperationStatus.OK, responses.get(0).getStatus(), "the settle made the first write durable");
        assertEquals(ErrorCode.WRITE_LOGGED_NOT_APPLIED.getCode(), responses.get(1).getErrorCode(),
                "a write whose commit threw part way has an unknown outcome");
        assertEquals("v", fixture.valueOf("kept"));
        assertEquals(OperationStatus.OK, fixture.save("after", "v").getStatus(), "the collection keeps working");
    }

    @Test
    public void aBatchThrowingInsideTheSettleWindowSettlesHoldingTheWriteLock() throws Exception {
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        TestUtils.setPrivateField(faultInjector, "halt", (IntConsumer) _ -> {
            throw new AssertionError("thrown right after the group's log force");
        });
        faultInjector.arm(FaultPoint.WAL_GROUP_FORCED, FaultAction.HALT, SUBJECT);
        final var lockedAtForce = new CopyOnWriteArrayList<Boolean>();
        recorder.refusingWhen((_, name) -> {
            if (GroupCommitFixture.WAL.equals(name)) {
                lockedAtForce.add(locks.isWriteLockedByCurrentThread(SUBJECT));
            }
            return false;
        });

        final var responses = fixture.runQueued(GroupCommitFixture.times(2, number -> fixture.save("w" + number, "v")));
        recorder.refusingWhen((_, _) -> false);

        assertTrue(responses.stream().allMatch(response -> response.getStatus() == OperationStatus.OK),
                String.valueOf(responses.stream().map(OperationResponse::getErrorCode).toList()));
        assertTrue(lockedAtForce.size() >= 2, String.valueOf(lockedAtForce));
        assertTrue(lockedAtForce.getLast(), "the settle after the throw holds the collection write lock");
        assertEquals("v", fixture.valueOf("w0"));
        assertEquals("v", fixture.valueOf("w1"));
    }

    @Test
    public void servedIsVolatile() throws Exception {
        final var served = GroupCommit.GroupedWrite.class.getDeclaredField("served");
        assertTrue(Modifier.isVolatile(served.getModifiers()));
    }

    @Test
    public void everyConcurrentSaveIsAnswered() throws Exception {
        final var saves = new ArrayList<CompletableFuture<OperationResponse>>();
        for (var index = 0; index < CONCURRENT_SAVES; index++) {
            final var id = "c" + index;
            saves.add(CompletableFuture.supplyAsync(() -> fixture.save(id, "v"),
                    command -> Thread.ofVirtual().start(command)));
        }
        for (final var save : saves) {
            final var response = save.get(GroupCommitFixture.WAIT_MS, TimeUnit.MILLISECONDS);
            assertEquals(OperationStatus.OK, response.getStatus(), String.valueOf(response.getMessage()));
        }
    }
}
