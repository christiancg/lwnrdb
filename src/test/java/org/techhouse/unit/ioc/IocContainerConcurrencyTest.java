package org.techhouse.unit.ioc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.ioc.IocContainer;

public class IocContainerConcurrencyTest {
    public static class SlowDependency {
        public SlowDependency() {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static class NestedDependency {
    }

    public static class OuterDependency {
        private final NestedDependency inner;

        public OuterDependency() {
            this.inner = IocContainer.get(NestedDependency.class);
        }

        public NestedDependency inner() {
            return inner;
        }
    }

    @Test
    public void test_concurrent_first_get_returns_one_instance() throws Exception {
        final var threads = 8;
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(threads);
        final Set<SlowDependency> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final var seenLock = new java.util.concurrent.locks.ReentrantLock();
        final var failure = new AtomicReference<Throwable>();

        for (var i = 0; i < threads; i++) {
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    final var instance = IocContainer.get(SlowDependency.class);
                    seenLock.lock();
                    try {
                        seen.add(instance);
                    } finally {
                        seenLock.unlock();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));

        assertNull(failure.get());
        assertEquals(1, seen.size(),
                "every caller must observe the same singleton: a split instance would give one holder of shared"
                        + " state such as the pending-write overlay a map the other never reads");
    }

    @Test
    public void test_recursive_construction_does_not_deadlock() {
        final var outer = IocContainer.get(OuterDependency.class);

        assertSame(IocContainer.get(NestedDependency.class), outer.inner(),
                "singleton constructors reach back into get(), so the publish cannot hold the map's own lock");
    }
}
