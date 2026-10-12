package org.techhouse.ops.auth;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.techhouse.config.Configuration;

public final class PasswordCheckGate {
    private final Semaphore permits;
    private final int size;

    public PasswordCheckGate() {
        this(Configuration.getInstance().getMaxConcurrentPasswordChecks());
    }

    public PasswordCheckGate(int configuredPermits) {
        this.size = configuredPermits > 0 ? configuredPermits : Math.max(1, Runtime.getRuntime().availableProcessors());
        this.permits = new Semaphore(size, true);
    }

    public int size() {
        return size;
    }

    public <T> T run(Supplier<T> work) throws InterruptedException {
        permits.acquire();
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
