package org.techhouse.cache;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class SizeCell {
    private final AtomicLong bytes = new AtomicLong();
    private final LongAdder tier;
    private volatile boolean retired;

    SizeCell(LongAdder tier) {
        this.tier = tier;
    }

    void add(long delta) {
        bytes.addAndGet(delta);
        tier.add(delta);
        if (retired) {
            drain();
        }
    }

    void set(long value) {
        tier.add(value - bytes.getAndSet(value));
        if (retired) {
            drain();
        }
    }

    void retire() {
        retired = true;
        drain();
    }

    long bytes() {
        return bytes.get();
    }

    private void drain() {
        tier.add(-bytes.getAndSet(0L));
    }
}
