package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

public class GroupCommitLoneWriteTest {
    private static final long WAIT_MS = GroupCommitFixture.WAIT_MS;
    private static final long BLOCKED_MS = 300L;
    private static final String SUBJECT = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    private static final String FIRST_PAGE = TestGlobals.COLL + "-0.dat";
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

    private static <T> CompletableFuture<T> inBackground(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, command -> Thread.ofVirtual().start(command));
    }

    private CompletableFuture<OperationResponse> pausedSeedSave(String value) {
        faultInjector.arm(FaultPoint.WAL_GROUP_APPENDED, FaultAction.PAUSE, SUBJECT);
        final var save = inBackground(() -> fixture.save("seed", value));
        assertTrue(faultInjector.awaitReached(FaultPoint.WAL_GROUP_APPENDED, WAIT_MS),
                "a lone write never reached the group force");
        return save;
    }

    private static AggregateRequest scanAll() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        return request;
    }

    @Test
    public void test_a_lone_write_logs_the_interval_base_once_then_forces_once() {
        assertEquals(OperationStatus.OK, fixture.save("seed", "v0").getStatus());
        final var firstForces = fixture.walForces();
        recorder.clear();

        assertEquals(OperationStatus.OK, fixture.save("next", "v1").getStatus());
        assertEquals(OperationStatus.OK, fixture.delete("seed").getStatus());

        assertEquals(2L, firstForces, "the interval's group base, then the write's own force");
        assertEquals(2L, fixture.walForces(), "one force per lone write once the base is logged");
        assertEquals("v1", fixture.valueOf("next"));
        assertNull(fixture.valueOf("seed"));
    }

    @Test
    public void test_find_by_id_passes_a_lone_write_while_its_force_is_paused() throws Exception {
        assertEquals(OperationStatus.OK, fixture.save("seed", "v0").getStatus());
        final var save = pausedSeedSave("lone");
        final var scan = inBackground(() -> fixture.process(scanAll()));

        assertEquals("lone", inBackground(() -> fixture.valueOf("seed")).get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> scan.get(BLOCKED_MS, TimeUnit.MILLISECONDS),
                "a scan waits for the lone write's blanks");
        assertFalse(save.isDone());

        faultInjector.release(FaultPoint.WAL_GROUP_APPENDED);
        assertEquals(OperationStatus.OK, save.get(WAIT_MS, TimeUnit.MILLISECONDS).getStatus());
        final var results = assertInstanceOf(AggregateResponse.class, scan.get(WAIT_MS, TimeUnit.MILLISECONDS))
                .getResults();
        assertEquals(1, results.size(), "the superseded copy was blanked before the scan read the page");
        assertEquals("lone", results.getFirst().get("v").asJsonString().getValue());
    }

    @Test
    public void test_a_lone_writes_failed_force_takes_back_under_the_write_lock() throws Exception {
        assertEquals(OperationStatus.OK, fixture.save("seed", "v0").getStatus());
        recorder.failingFor(GroupCommitFixture.WAL);
        final var save = pausedSeedSave("lost");
        final var readerHolds = new CountDownLatch(1);
        final var readerRelease = new CountDownLatch(1);
        final var reader = inBackground(() -> {
            try {
                final var held = locks.acquirePointReadLock(false, SUBJECT, 0L);
                readerHolds.countDown();
                readerRelease.await();
                locks.releasePointReadLocks(held);
                return held;
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(readerHolds.await(WAIT_MS, TimeUnit.MILLISECONDS), "a point read enters the lone write's window");

        faultInjector.release(FaultPoint.WAL_GROUP_APPENDED);
        assertThrows(TimeoutException.class, () -> save.get(BLOCKED_MS, TimeUnit.MILLISECONDS),
                "the take-back waits for the point reader to leave");
        readerRelease.countDown();
        reader.get(WAIT_MS, TimeUnit.MILLISECONDS);
        recorder.failingFor(null);

        assertEquals(ErrorCode.ERROR_SAVING.getCode(), save.get(WAIT_MS, TimeUnit.MILLISECONDS).getErrorCode());
        assertEquals("v0", fixture.valueOf("seed"));
        assertEquals(OperationStatus.OK, fixture.save("after", "v").getStatus(), "the collection keeps working");
        assertEquals("v", fixture.valueOf("after"));
    }

    @Test
    public void test_the_writes_after_a_page_rewrite_share_one_reopened_group() throws Exception {
        assertEquals(OperationStatus.OK, fixture.save("only", "v0").getStatus());
        final var responses = fixture.runQueued(List.of(() -> fixture.delete("only"), () -> fixture.save("a", "v"),
                () -> fixture.save("b", "v"), () -> fixture.save("c", "v")));

        assertTrue(responses.stream().allMatch(response -> response.getStatus() == OperationStatus.OK));
        final var forced = recorder.forcedNames();
        final var lastPageForce = forced.lastIndexOf(FIRST_PAGE);
        assertTrue(lastPageForce >= 0, "deleting the page's only document rewrote the page inside the group");
        final var logForcesAfterRewrite = forced.subList(lastPageForce, forced.size()).stream()
                .filter(GroupCommitFixture.WAL::equals).count();
        assertEquals(2L, logForcesAfterRewrite, "the rewrite's end, then one force for the three saves after it");
        for (final var id : List.of("a", "b", "c")) {
            assertEquals("v", fixture.valueOf(id));
        }
        assertNull(fixture.valueOf("only"));
    }

    @Test
    public void test_a_failed_force_after_a_page_rewrite_fails_only_the_writes_of_the_reopened_group()
            throws Exception {
        assertEquals(OperationStatus.OK, fixture.save("only", "v0").getStatus());
        recorder.refusingWhen((forced, name) -> GroupCommitFixture.WAL.equals(name)
                && forced.lastIndexOf(FIRST_PAGE) >= 0 && forced.subList(forced.lastIndexOf(FIRST_PAGE), forced.size())
                        .stream().filter(GroupCommitFixture.WAL::equals).count() == 1L);

        final var responses = fixture.runQueued(List.of(() -> fixture.delete("only"), () -> fixture.save("a", "v"),
                () -> fixture.save("b", "v"), () -> fixture.save("c", "v")));
        recorder.refusingWhen((_, _) -> false);

        assertEquals(OperationStatus.OK, responses.getFirst().getStatus(),
                "the delete was forced by the rewrite that ended its group, so it stands");
        for (final var response : responses.subList(1, responses.size())) {
            assertEquals(ErrorCode.ERROR_SAVING.getCode(), response.getErrorCode(),
                    "a write of the reopened group whose force failed is taken back");
        }
        assertNull(fixture.valueOf("only"));
        for (final var id : List.of("a", "b", "c")) {
            assertNull(fixture.valueOf(id));
        }
        assertEquals(OperationStatus.OK, fixture.save("after", "v").getStatus(), "the collection keeps working");
    }
}
