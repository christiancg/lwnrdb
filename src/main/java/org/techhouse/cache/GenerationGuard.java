package org.techhouse.cache;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

final class GenerationGuard {
    private final AtomicLong generation = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();

    long current() {
        return generation.get();
    }

    void publishIfCurrent(long seenAtLoad, Runnable publish) {
        lock.lock();
        try {
            if (generation.get() == seenAtLoad) {
                publish.run();
            }
        } finally {
            lock.unlock();
        }
    }

    void invalidate(Runnable mutation) {
        lock.lock();
        try {
            generation.incrementAndGet();
            mutation.run();
        } finally {
            lock.unlock();
        }
    }
}
