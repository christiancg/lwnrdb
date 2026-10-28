package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class ApplyLanesTest {
    private static final int TASKS = 200;

    @Test
    public void test_tasks_of_one_key_run_in_submission_order() {
        final var ran = Collections.synchronizedList(new ArrayList<Integer>());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var lanes = new ApplyLanes(executor);
            for (var i = 0; i < TASKS; i++) {
                final var index = i;
                lanes.submit("db|a", () -> ran.add(index));
            }
        }

        final var expected = new ArrayList<Integer>();
        for (var i = 0; i < TASKS; i++) {
            expected.add(i);
        }
        assertEquals(expected, ran);
    }

    @Test
    public void test_a_blocked_key_does_not_block_another() throws Exception {
        final var release = new CountDownLatch(1);
        final var otherRan = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var lanes = new ApplyLanes(executor);
            lanes.submit("db|a", () -> await(release));
            lanes.submit("db|b", otherRan::countDown);

            assertTrue(otherRan.await(5, TimeUnit.SECONDS), "a lane parked on one collection held up another");
            release.countDown();
        }
    }

    @Test
    public void test_a_key_is_removed_once_its_lane_is_empty() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var lanes = new ApplyLanes(executor);
            final var done = new CountDownLatch(2);
            lanes.submit("db|a", done::countDown);
            lanes.submit("db|b", done::countDown);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (lanes.activeLanes() > 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(0, lanes.activeLanes(), "an idle lane is not kept");
        }
    }

    @Test
    public void test_tasks_submitted_while_a_lane_drains_run_after_it_in_order() throws Exception {
        final var release = new CountDownLatch(1);
        final var started = new CountDownLatch(1);
        final var ran = Collections.synchronizedList(new ArrayList<String>());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var lanes = new ApplyLanes(executor);
            lanes.submit("db|a", () -> {
                started.countDown();
                await(release);
                ran.add("first");
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            lanes.submit("db|a", () -> ran.add("second"));
            lanes.submit("db|a", () -> ran.add("third"));
            assertEquals(1, lanes.activeLanes(), "the queued tasks join the running lane");
            assertFalse(ran.contains("second"));
            release.countDown();
        }

        assertEquals(List.of("first", "second", "third"), ran);
    }

    @Test
    public void test_a_failing_task_does_not_stall_its_lane() throws Exception {
        final var after = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var lanes = new ApplyLanes(executor);
            lanes.submit("db|a", () -> {
                throw new IllegalStateException("boom");
            });
            lanes.submit("db|a", after::countDown);

            assertTrue(after.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void test_a_task_throwing_an_error_does_not_wedge_its_key() throws Exception {
        final var after = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var lanes = new ApplyLanes(executor);
            lanes.submit("db|a", () -> {
                throw new StackOverflowError("deep document");
            });
            lanes.submit("db|a", after::countDown);
            assertTrue(waitForIdle(lanes), "the key stayed registered after its drain met an Error");
            lanes.submit("db|a", after::countDown);

            assertTrue(after.await(5, TimeUnit.SECONDS), "a task queued behind an Error never ran");
        }
    }

    private static boolean waitForIdle(ApplyLanes lanes) {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (lanes.activeLanes() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return lanes.activeLanes() == 0;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
