package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
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
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class GroupCommitWindowTest {
    private static final long WAIT_MS = GroupCommitFixture.WAIT_MS;
    private static final long BLOCKED_MS = 300L;
    private static final String SUBJECT = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private RecordingForcer recorder;
    private GroupCommitFixture fixture;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        recorder = RecordingForcer.install(true);
        fixture = new GroupCommitFixture(recorder);
        assertEquals(OperationStatus.OK, fixture.save("seed", "v0").getStatus());
        TestUtils.setPrivateField(faultInjector, "enabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
        recorder.close();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private List<Supplier<OperationResponse>> fourSaves() {
        final var writes = new ArrayList<Supplier<OperationResponse>>();
        writes.add(() -> fixture.save("seed", "grouped"));
        writes.addAll(GroupCommitFixture.times(3, number -> fixture.save("w" + number, "v" + number)));
        return writes;
    }

    private CompletableFuture<List<OperationResponse>> pausedGroup() {
        faultInjector.arm(FaultPoint.WAL_GROUP_APPENDED, FaultAction.PAUSE, SUBJECT);
        final var group = fixture.runQueuedInBackground(fourSaves());
        assertTrue(faultInjector.awaitReached(FaultPoint.WAL_GROUP_APPENDED, WAIT_MS), "the group never forced");
        return group;
    }

    private static <T> CompletableFuture<T> inBackground(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, command -> Thread.ofVirtual().start(command));
    }

    private ReentrantReadWriteLock collectionLock() throws Exception {
        final var type = new ReflectionUtils.TypeToken<Map<String, ReentrantReadWriteLock>>() {
        };
        return TestUtils.getPrivateField(locks, "locks", type).get(SUBJECT);
    }

    private static AggregateRequest scanAll() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        return request;
    }

    @Test
    public void test_find_by_id_sees_the_grouped_value_while_the_force_is_paused() throws Exception {
        final var group = pausedGroup();

        assertEquals("grouped", inBackground(() -> fixture.valueOf("seed")).get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals("v1", inBackground(() -> fixture.valueOf("w1")).get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertFalse(group.isDone());

        faultInjector.release(FaultPoint.WAL_GROUP_APPENDED);
        assertTrue(group.get(WAIT_MS, TimeUnit.MILLISECONDS).stream()
                .allMatch(response -> response.getStatus() == OperationStatus.OK));
        assertEquals(1L, fixture.walForces(), "the seed logged the interval's group base, so one force for the group");
    }

    @Test
    public void test_an_aggregate_waits_until_the_blanks_land() throws Exception {
        final var group = pausedGroup();
        final var scan = inBackground(() -> fixture.process(scanAll()));

        assertThrows(TimeoutException.class, () -> scan.get(BLOCKED_MS, TimeUnit.MILLISECONDS));
        faultInjector.release(FaultPoint.WAL_GROUP_APPENDED);
        group.get(WAIT_MS, TimeUnit.MILLISECONDS);

        final var results = assertInstanceOf(AggregateResponse.class, scan.get(WAIT_MS, TimeUnit.MILLISECONDS))
                .getResults();
        assertEquals(4, results.size(), "every document once, no superseded copy");
        assertTrue(
                results.stream().anyMatch(document -> "grouped".equals(document.get("v").asJsonString().getValue())));
    }

    @Test
    public void test_followers_never_queue_on_the_collection_lock() throws Exception {
        final var group = pausedGroup();
        final var late = new ArrayList<CompletableFuture<OperationResponse>>();
        for (var number = 0; number < 3; number++) {
            final var id = "late" + number;
            late.add(inBackground(() -> fixture.save(id, "v")));
        }
        GroupCommitFixture.awaitQueued(3);

        assertFalse(collectionLock().hasQueuedThreads(), "followers wait on the group's turn, not the lock");
        faultInjector.release(FaultPoint.WAL_GROUP_APPENDED);
        group.get(WAIT_MS, TimeUnit.MILLISECONDS);
        for (final var write : late) {
            assertEquals(OperationStatus.OK, write.get(WAIT_MS, TimeUnit.MILLISECONDS).getStatus());
        }
        assertEquals("v", fixture.valueOf("late2"));
        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL), "no writer stranded the collection lock");
        locks.release(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_a_failed_force_takes_back_under_the_write_lock() throws Exception {
        assertTrue(fixture.runQueued(GroupCommitFixture.times(2, number -> fixture.save("base" + number, "v"))).stream()
                .allMatch(response -> response.getStatus() == OperationStatus.OK), "logs the group base");
        recorder.failingFor(GroupCommitFixture.WAL);
        final var group = pausedGroup();
        final var readerHolds = new CountDownLatch(1);
        final var readerRelease = new CountDownLatch(1);
        final var reader = inBackground(() -> {
            try {
                final var held = locks.acquirePointReadLock(false, SUBJECT, 0L);
                readerHolds.countDown();
                readerRelease.await();
                locks.releaseReadLocks(held);
                return held;
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(readerHolds.await(WAIT_MS, TimeUnit.MILLISECONDS), "a point read enters the window");

        faultInjector.release(FaultPoint.WAL_GROUP_APPENDED);
        assertThrows(TimeoutException.class, () -> group.get(BLOCKED_MS, TimeUnit.MILLISECONDS),
                "the take-back waits for the point reader to leave");
        assertFalse(reader.isDone());
        readerRelease.countDown();
        reader.get(WAIT_MS, TimeUnit.MILLISECONDS);
        recorder.failingFor(null);

        final var responses = group.get(WAIT_MS, TimeUnit.MILLISECONDS);
        assertTrue(responses.stream()
                .allMatch(response -> ErrorCode.ERROR_SAVING.getCode().equals(response.getErrorCode())));
        assertEquals("v0", fixture.valueOf("seed"));
        assertNull(fixture.valueOf("w0"));
        assertEquals(OperationStatus.OK, fixture.save("after", "v").getStatus(), "the collection keeps working");
    }
}
