package org.techhouse.concurrency;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public class SharedByteBudget {
    private final AtomicLong reserved = new AtomicLong();
    private final LongSupplier limit;

    public SharedByteBudget(LongSupplier limit) {
        this.limit = limit;
    }

    public boolean tryReserve(long bytes) {
        final var cap = limit.getAsLong();
        while (true) {
            final var current = reserved.get();
            if (current + bytes > cap) {
                return false;
            }
            if (reserved.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    public void release(long bytes) {
        if (bytes != 0) {
            reserved.addAndGet(-bytes);
        }
    }

    public long reservedBytes() {
        return reserved.get();
    }
}
