package org.techhouse.concurrency;

import java.io.Serial;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ReadTrackingLock extends ReentrantReadWriteLock {
    @Serial
    private static final long serialVersionUID = 1L;
    private final AtomicInteger longReaders = new AtomicInteger();

    public int longReaders() {
        return longReaders.get();
    }

    void longReadGranted() {
        longReaders.incrementAndGet();
    }

    void longReadReleased() {
        longReaders.getAndUpdate(held -> Math.max(held - 1, 0));
    }

    boolean bargeForPoint() {
        return longReaders.get() > 0 && readLock().tryLock();
    }
}
