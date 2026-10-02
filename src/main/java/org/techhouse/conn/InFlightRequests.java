package org.techhouse.conn;

import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.bckg_ops.IdleSignal;

public class InFlightRequests {
    private final AtomicInteger count = new AtomicInteger();
    private final IdleSignal idleSignal = new IdleSignal();

    public void enter() {
        count.incrementAndGet();
    }

    public void exit() {
        count.decrementAndGet();
        idleSignal.signal();
    }

    public int current() {
        return count.get();
    }

    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        return idleSignal.awaitIdle(() -> count.get() == 0, timeoutMillis);
    }
}
