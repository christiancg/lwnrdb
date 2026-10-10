package org.techhouse.fault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    public static String collectionSubject(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
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
        return fired.action() == FaultAction.DROP;
    }

    public void arm(FaultPoint point, FaultAction action, String match, long delayMs) {
        final var previous = armed.put(point, new ArmedFault(action, match, delayMs));
        if (previous != null) {
            previous.release();
        }
    }

    public boolean awaitReached(FaultPoint point, long timeoutMs) {
        final var fault = armed.get(point);
        return fault != null && fault.awaitReached(timeoutMs);
    }

    public void release(FaultPoint point) {
        final var fault = armed.remove(point);
        if (fault != null) {
            fault.release();
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
        switch (fired.action()) {
            case HALT -> halt.accept(FAULT_HALT_STATUS);
            case PAUSE, DELAY -> fired.hold(pauseLimitMs);
            default -> {
            }
        }
    }
}
