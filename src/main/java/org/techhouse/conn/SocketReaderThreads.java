package org.techhouse.conn;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SocketReaderThreads {
    private static final boolean PLATFORM = System.getProperty("os.name", "").startsWith("Windows");

    private SocketReaderThreads() {
    }

    public static ExecutorService newPerTaskExecutor() {
        return newPerTaskExecutor(PLATFORM);
    }

    static ExecutorService newPerTaskExecutor(boolean platform) {
        return platform
                ? Executors.newThreadPerTaskExecutor(
                        Thread.ofPlatform().daemon().name("lwnrdb-socket-reader-", 0).factory())
                : Executors.newVirtualThreadPerTaskExecutor();
    }

    public static Thread start(String name, Runnable task) {
        return start(PLATFORM, name, task);
    }

    static Thread start(boolean platform, String name, Runnable task) {
        return platform
                ? Thread.ofPlatform().daemon().name(name).start(task)
                : Thread.ofVirtual().name(name).start(task);
    }
}
