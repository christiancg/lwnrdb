package org.techhouse.ops.auth;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.techhouse.config.Configuration;

public final class PasswordCheckGate {
    public static final String THREAD_NAME_PREFIX = "lwnrdb-pbkdf2-";
    private static final long IDLE_THREAD_KEEP_ALIVE_SECONDS = 60;
    private static final Configuration configuration = Configuration.getInstance();

    private final Semaphore permits;
    private final int size;
    private final ExecutorService hashers;

    public PasswordCheckGate() {
        this(configuration.getMaxConcurrentPasswordChecks());
    }

    public PasswordCheckGate(int configuredPermits) {
        this.size = configuredPermits > 0 ? configuredPermits : Math.max(1, Runtime.getRuntime().availableProcessors());
        this.permits = new Semaphore(size, true);
        this.hashers = hasherPool(size);
    }

    private static ExecutorService hasherPool(int threads) {
        final var pool = new ThreadPoolExecutor(threads, threads, IDLE_THREAD_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), daemonThreads());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static ThreadFactory daemonThreads() {
        final var counter = new AtomicInteger();
        return work -> Thread.ofPlatform().daemon().name(THREAD_NAME_PREFIX + counter.incrementAndGet())
                .unstarted(work);
    }

    public int size() {
        return size;
    }

    public <T> T run(Supplier<T> work) throws InterruptedException {
        permits.acquire();
        try {
            return awaitOnHasher(hashers.submit(work::get));
        } finally {
            permits.release();
        }
    }

    private static <T> T awaitOnHasher(Future<T> pending) throws InterruptedException {
        try {
            return pending.get();
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw rethrown(e.getCause());
        }
    }

    private static RuntimeException rethrown(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(cause);
    }
}
