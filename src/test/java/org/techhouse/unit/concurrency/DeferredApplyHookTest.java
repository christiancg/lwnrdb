package org.techhouse.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.DeferredApplyHook;
import org.techhouse.concurrency.ResourceLocking;

public class DeferredApplyHookTest {
    private static final String ONE = "hookdb|one";
    private static final String TWO = "hookdb|two";

    private final ResourceLocking locks = new ResourceLocking();
    private final AtomicInteger applies = new AtomicInteger();

    @AfterEach
    void tearDown() {
        ResourceLocking.useDeferredApplyHook(null);
        for (final var name : List.of(ONE, TWO)) {
            while (locks.isWriteLockedByCurrentThread(name)) {
                locks.releaseWrite(name);
            }
            locks.releaseReadByName(name);
        }
    }

    private void installHook(Set<String> pending, Throwable failure) {
        ResourceLocking.useDeferredApplyHook(new DeferredApplyHook() {
            @Override
            public boolean pending(String lockName) {
                return pending.contains(lockName);
            }

            @Override
            public void apply(String lockName) {
                applies.incrementAndGet();
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
            }
        });
    }

    @Test
    public void aHookThatThrowsLeavesNoWriteLockHeldByAMultiLockAcquire() throws Exception {
        installHook(Set.of(ONE, TWO), new IllegalStateException("apply failed"));

        try {
            locks.withWriteLocks(List.of(ONE, TWO), 500L, () -> null);
        } catch (RuntimeException ignored) {
            assertFalse(locks.isWriteLockedByCurrentThread(ONE), "a throw must not strand the first lock");
        }

        assertFalse(locks.isWriteLockedByCurrentThread(ONE));
        assertFalse(locks.isWriteLockedByCurrentThread(TWO));
    }

    @Test
    public void aHookThatThrowsAnErrorStillGrantsTheLockItRanUnder() {
        installHook(Set.of(ONE), new AssertionError("apply failed"));

        assertTrue(locks.tryLockWrite("hookdb", "one"));
        assertTrue(locks.isWriteLockedByCurrentThread(ONE));
        locks.releaseWrite(ONE);

        assertFalse(locks.isWriteLockedByCurrentThread(ONE));
        assertEquals(1, applies.get());
    }

    @Test
    public void aZeroBudgetReadTryNeverRunsTheApply() throws Exception {
        installHook(Set.of(ONE), null);

        assertTrue(locks.tryLockRead("hookdb", "one", 0L));
        locks.releaseRead("hookdb", "one");

        assertEquals(0, applies.get(), "a zero-budget try must not upgrade to apply the logged writes");
    }

    @Test
    public void aBudgetedReadUpgradesToApply() throws Exception {
        installHook(Set.of(ONE), null);

        assertTrue(locks.tryLockRead("hookdb", "one", 500L));
        locks.releaseRead("hookdb", "one");

        assertEquals(1, applies.get());
    }
}
