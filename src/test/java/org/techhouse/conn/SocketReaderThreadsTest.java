package org.techhouse.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

public class SocketReaderThreadsTest {
    private record Observed(boolean virtual, boolean daemon, String name) {
        static Observed current() {
            final var thread = Thread.currentThread();
            return new Observed(thread.isVirtual(), thread.isDaemon(), thread.getName());
        }
    }

    @Test
    public void test_a_windows_reader_pool_runs_each_task_on_a_daemon_platform_thread()
            throws InterruptedException, ExecutionException, TimeoutException {
        final var observed = observedIn(true);
        assertFalse(observed.virtual());
        assertTrue(observed.daemon());
        assertTrue(observed.name().startsWith("lwnrdb-socket-reader-"), observed.name());
    }

    @Test
    public void test_elsewhere_the_reader_pool_runs_each_task_on_a_virtual_thread()
            throws InterruptedException, ExecutionException, TimeoutException {
        assertTrue(observedIn(false).virtual());
    }

    @Test
    public void test_a_started_reader_thread_is_named_and_platform_only_on_windows() throws InterruptedException {
        final var platform = SocketReaderThreads.start(true, "peer-reader", () -> {
        });
        final var virtual = SocketReaderThreads.start(false, "peer-reader", () -> {
        });
        platform.join();
        virtual.join();
        assertFalse(platform.isVirtual());
        assertTrue(platform.isDaemon());
        assertTrue(virtual.isVirtual());
        assertEquals("peer-reader", platform.getName());
        assertEquals("peer-reader", virtual.getName());
    }

    @Test
    public void test_the_default_follows_the_operating_system() throws InterruptedException {
        final var thread = SocketReaderThreads.start("probe", () -> {
        });
        thread.join();
        assertEquals(!System.getProperty("os.name", "").startsWith("Windows"), thread.isVirtual());
    }

    private static Observed observedIn(boolean platform)
            throws InterruptedException, ExecutionException, TimeoutException {
        try (var pool = SocketReaderThreads.newPerTaskExecutor(platform)) {
            final var observed = new CompletableFuture<Observed>();
            pool.execute(() -> observed.complete(Observed.current()));
            return observed.get(10, TimeUnit.SECONDS);
        }
    }
}
