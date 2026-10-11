package org.techhouse.fault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;

public class FaultInjector {
    private static final int FAULT_HALT_STATUS = 137;

    private final Map<FaultPoint, ArmedFault> armed = new ConcurrentHashMap<>();
    @SuppressWarnings("FieldMayBeFinal")
    private volatile boolean enabled = Configuration.getInstance().isFaultInjectionEnabled();
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long pauseLimitMs = Globals.FAULT_PAUSE_LIMIT_MS;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile IntConsumer halt = Runtime.getRuntime()::halt;

    public static <E extends Enum<E>> E parse(Class<E> type, String name) {
        for (final var constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(name)) {
                return constant;
            }
        }
        return null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void at(FaultPoint point, String subject) {
        if (!enabled) {
            return;
        }
        final var fired = fire(point, subject);
        if (fired != null) {
            perform(fired);
        }
    }

    public boolean dropsAt(FaultPoint point, String subject) {
        if (!enabled) {
            return false;
        }
        final var fired = fire(point, subject);
        if (fired == null) {
            return false;
        }
        perform(fired);
        return fired.action == FaultAction.DROP;
    }

    public void arm(FaultPoint point, FaultAction action, String match) {
        final var previous = armed.put(point, new ArmedFault(action, match));
        if (previous != null) {
            previous.released.countDown();
        }
    }

    public boolean awaitReached(FaultPoint point, long timeoutMs) {
        final var fault = armed.get(point);
        return fault != null && awaitQuietly(fault.reached, timeoutMs);
    }

    public void release(FaultPoint point) {
        final var fault = armed.remove(point);
        if (fault != null) {
            fault.released.countDown();
        }
    }

    public void releaseAll() {
        for (final var point : FaultPoint.values()) {
            release(point);
        }
    }

    private ArmedFault fire(FaultPoint point, String subject) {
        final var fault = armed.get(point);
        return fault != null && fault.tryFire(subject) ? fault : null;
    }

    private void perform(ArmedFault fired) {
        switch (fired.action) {
            case HALT -> halt.accept(FAULT_HALT_STATUS);
            case PAUSE -> awaitQuietly(fired.released, pauseLimitMs);
            default -> {
            }
        }
    }

    private static boolean awaitQuietly(CountDownLatch latch, long timeoutMs) {
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final class ArmedFault {
        private final FaultAction action;
        private final String match;
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean fired = new AtomicBoolean();

        private ArmedFault(FaultAction action, String match) {
            this.action = action;
            this.match = match;
        }

        private boolean tryFire(String subject) {
            if ((match != null && !match.equals(subject)) || !fired.compareAndSet(false, true)) {
                return false;
            }
            reached.countDown();
            return true;
        }
    }
}
