package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.Tx2pcRecovery;
import org.techhouse.ex.DurableReplayIncompleteException;
import org.techhouse.ioc.IocContainer;

public class Tx2pcRecoveryThreadTest {
    private final Tx2pcRecovery recovery = IocContainer.get(Tx2pcRecovery.class);

    private Thread recoveryThreadSeenFrom(Thread.Builder caller) throws Exception {
        final var seen = new AtomicReference<Thread>();
        final var failure = new AtomicReference<Exception>();
        caller.start(() -> {
            try {
                seen.set(recovery.onRecoveryThread(Thread::currentThread, 5000L));
            } catch (Exception e) {
                failure.set(e);
            }
        }).join(10_000);
        if (failure.get() != null) {
            throw failure.get();
        }
        return seen.get();
    }

    @Test
    public void test_every_entry_point_runs_on_one_recovery_thread() throws Exception {
        final var fromPlatform = recoveryThreadSeenFrom(Thread.ofPlatform());
        final var fromVirtual = recoveryThreadSeenFrom(Thread.ofVirtual());
        final var fromTestThread = recovery.onRecoveryThread(Thread::currentThread, 5000L);

        assertSame(fromPlatform, fromVirtual);
        assertSame(fromPlatform, fromTestThread);
        assertTrue(fromPlatform.getName().startsWith("tx2pc-recovery-"));
    }

    @Test
    public void test_a_membership_change_recovers_on_the_recovery_thread() throws Exception {
        final var recoveryThread = recovery.onRecoveryThread(Thread::currentThread, 5000L);

        recovery.onMembershipChanged(new MembershipView(java.util.List.of()));

        assertSame(recoveryThread, recovery.onRecoveryThread(Thread::currentThread, 5000L),
                "the membership-triggered run shares the thread, so it cannot strand another attempt's locks");
    }

    @Test
    public void test_a_nested_call_runs_inline_on_the_recovery_thread() throws Exception {
        final var nested = recovery.onRecoveryThread(() -> recovery.onRecoveryThread(Thread::currentThread, 1000L),
                5000L);

        assertSame(recovery.onRecoveryThread(Thread::currentThread, 5000L), nested);
    }

    @Test
    public void test_a_failure_surfaces_as_its_own_exception() {
        assertThrows(DurableReplayIncompleteException.class, () -> recovery.onRecoveryThread(() -> {
            throw new DurableReplayIncompleteException("dtx", "its ops did not all apply");
        }, 5000L));
    }

    @Test
    public void test_an_error_surfaces_unwrapped() {
        assertThrows(StackOverflowError.class, () -> recovery.onRecoveryThread(() -> {
            throw new StackOverflowError("script blew the stack");
        }, 5000L));
    }

    @Test
    public void test_a_busy_recovery_thread_times_the_caller_out() throws Exception {
        final var occupied = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var blocker = Thread.ofVirtual().start(() -> {
            try {
                recovery.onRecoveryThread(() -> {
                    occupied.countDown();
                    return release.await(10, TimeUnit.SECONDS);
                }, 0L);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> recovery.onRecoveryThread(() -> true, 50L));
        } finally {
            release.countDown();
            blocker.join(10_000);
        }
    }

    @Test
    public void test_a_stopped_recovery_still_serves_a_replay_on_one_thread() throws Exception {
        final var stoppable = new Tx2pcRecovery();
        final var before = stoppable.onRecoveryThread(Thread::currentThread, 5000L);

        stoppable.stop();
        final var afterStop = stoppable.onRecoveryThread(Thread::currentThread, 5000L);

        assertTrue(afterStop.getName().startsWith("tx2pc-recovery-"));
        assertSame(afterStop, stoppable.onRecoveryThread(Thread::currentThread, 5000L));
        assertNotSame(before, afterStop, "the stopped thread is replaced rather than reused");
        stoppable.stop();
    }
}
